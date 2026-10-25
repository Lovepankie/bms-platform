# Runbook: deploy

**Applies to:** staging and production · **Decisions:** ADR-006, ADR-018, ADR-078 · **Detail:** `docs/sdd/10-cicd-pipeline.md`

## Normal path (no host access needed)

| Target | How |
|---|---|
| Staging | Merge a pull request to `main`. `deploy.yml` builds `sha-<7>` on the deploy runner (docs/runbooks/deploy-runner.md), the signer on the maintainer's Mac signs the three digests (docs/runbooks/deploy-signer.md), and only then `staging-pointer` moves the pointer; the staging host's timer notices within two minutes and deploys it (pull model, ADR-018, ADR-040). Release tags are not deployable through the runner group yet (it admits only `refs/heads/main`; ADR-040). The Mac must be on: if the signatures do not appear within 20 minutes the pointer job fails and staging stays on the last release. |
| Production | On a commit already green on staging: `git tag vX.Y.Z <commit>` then `git push origin vX.Y.Z`. Approve the `production` deployment in the Actions run when asked. |

Then check:

1. The Actions run is green. On `main` the last job says "staging now names sha-<7>". On a tag, a
   notice "not provisioned" means the production secrets are not set; images were still tagged.
2. A few minutes later, `curl -s https://<platform host>/version` shows the expected `git_sha`
   (`sha-<7>`): `bms-staging.rincoltech.com` on staging, `bms.rincoltech.com` in production.
3. `curl -s https://<platform host>/readyz` returns `{"status":"UP"}`.
4. For production, announce the release to the team.

## Staging: what the host did

The staging host cannot be reached from GitHub, so its result is on the host (as `bms`, or root):

```bash
journalctl -u bms-pull.service --since '-30 min' --no-pager   # the puller's and deploy.sh's log
tail -n 20 /opt/bms/state/history.log
cat /opt/bms/state/current_tag /opt/bms/state/last_failed_tag 2>/dev/null
systemctl list-timers bms-pull.timer
```

- Nothing happens after a merge: check the pointer moved (the `Point staging at sha-<7>` job) and
  that `docker pull ghcr.io/rincoltech-solutions-ltd/bms-platform-api:staging` works on the host as
  `bms` (the packages are private; `bms` needs the `read:packages` login of
  `docs/runbooks/provision-host.md` section 8.1a).
- `state/last_failed_tag` holds the tag: that release failed and is not retried. Check
  `state/history.log` for why: `failed-signature` means a cosign verification did not pass (check
  `/opt/bms/cosign.pub` is the real key, not the placeholder); `refused-downgrade` means the release
  was built before the one already live (`touch /opt/bms/state/allow_downgrade` to deploy it
  anyway). Fix forward with a new merge (a new tag is tried at once), or retry the same one with
  `rm /opt/bms/state/last_failed_tag`. `releases/<tag>/` on the host keeps that release's files for
  inspection until the next successful deploy cleans it up.
- To pause staging deploys: `systemctl stop bms-pull.timer` (and `start` to resume).

## Old app copies after a deploy (ADR-078)

Before `web` is replaced, `deploy.sh` copies the outgoing build's hashed files into the volume
`bms_web_assets`, so a phone still on the previous app copy keeps loading its screens. The deploy log
shows `kept <n> hashed files of earlier builds (<size>)`; a line starting `warning: the outgoing web
files were not kept` means only that old copies may need a reload, and the deploy went on (the step
failed, or ran past its 60 seconds, `BMS_KEEP_ASSETS_TIMEOUT`). Files leave the volume 14 days after
they leave the live build (`BMS_KEEP_ASSETS_DAYS` in `.env`, a whole number of days, changes it).

Manual check after a deploy, from any machine (never needed for a normal deploy):

1. Before the deploy, note one hashed name the live build uses:
   `curl -s https://<platform host>/ | grep -o '/assets/index-[^"]*\.js'`.
2. After the deploy, the same command shows a new name, and the old one still loads:
   `curl -sI https://<platform host>/assets/index-<old hash>.js` answers `200` with
   `cache-control: public, max-age=31536000, immutable`.
3. `curl -sI https://<platform host>/` and `curl -sI https://<platform host>/sw.js` answer
   `cache-control: no-cache`.
4. On the host, what is kept: `docker compose --project-name bms -f /opt/bms/compose.yml exec web du -sh /srv/previous`.

The volume holds copies only; deleting it (`docker volume rm bms_web_assets` while `web` is stopped)
loses nothing but those copies. The CI test `deploy/tests/keep-assets-test.sh` runs the same switch
against both compose files with three small builds.

## No gap during the switch (ADR-083)

`deploy.sh` starts the new `api` and `web` container beside the running one and stops the old one
only once the new one is healthy, so the site keeps answering through a deploy and a rollback. The
deploy log shows `starting a new api container beside the running one` and `api switched` (the
same for web), and `proxy unchanged; kept running` when the proxy image holds the same caddy and
Caddyfiles. Lines to know:

- `the new api container is not healthy; removing it, the old one keeps serving`: the release did
  not start. Its last 40 log lines follow; the previous release is still live and the rollback
  finds nothing to change.
- `<n> MB free, a second api needs <m> MB; restarting api in place (a short gap)`: the host, or
  `bms.slice` below its `MemoryHigh`, had less than the api's own memory limit plus 64 MB free, so
  the api restarted as before. Caddy holds requests for up to 30 seconds and the app says it is
  updating. On the Pi this is not expected since the slice was raised to 1500/1600 MB (decision
  D37): measured 24 Oct 2026 at about 790 MB in use, it has about 710 MB free below `MemoryHigh`,
  and a normal deploy runs two `api` containers for the length of the switch. If this line appears,
  check `cat /sys/fs/cgroup/bms.slice/memory.current` and what grew in the slice.
  `BMS_ROLL_MIN_FREE_MB` in `.env` can replace the api's limit with a measured start-up peak plus a
  margin.
- `warning: the incoming web files were not kept; restarting web in place (a short gap)`: the
  new build's hashed files could not be copied into `bms_web_assets` before the switch, so web was
  not overlapped (the old container could not have served them). Nothing else to do; check disk space
  if it repeats.
- During a switch `docker compose ps` shows two `api` (or `web`) containers, numbered on; that is
  expected. Two left after a deploy means it was interrupted: the next deploy removes the extra one.

The CI test `deploy/tests/no-gap-test.sh` drives the same switch on both compose files under GET
and POST traffic and fails on any answer that is not 200 within 3 seconds.

## Old apps update by themselves; forcing it (ADR-088)

Every API answer carries `Bms-Build` (the `git_sha` of `/version`). A phone whose app is another
build fetches the new one in the background and reloads by itself at a safe moment, with a short
"Updating the app…" toast; a cart on Sell or a form with typed changes holds the reload until the
next move between screens. Nothing to do for a normal deploy.

**`BMS_MIN_CLIENT_BUILD`** (API, `.env`, empty by default) makes it immediate: an app built before
it is answered 426 `app_too_old` and shows "A new version of the app is ready. Updating…" until the
new app has loaded. Set it only for a release that changes the API in a way the previous screens
cannot handle (a field removed or renamed, a request shape changed, a new required field), never for
an ordinary release.

1. Deploy the release as usual and check that it is live: `curl -s https://<platform host>/version`
   names its sha tag. Setting the value before the new web build is served would send every phone
   to the same old build (one reload a minute stops a loop, but the app would show the update screen).
2. Take the release commit's time, UTC: `TZ=UTC git show -s --date=format-local:%Y%m%d%H%M%S --format=%cd <commit>`
   (for example `20261025140000`). Any other format stops the API at start, and the old release keeps
   serving.
3. Set `BMS_MIN_CLIENT_BUILD=<that time>` in `/opt/bms/.env` and recreate the api the usual way
   (`deploy.sh` with the same tag). Check:
   `curl -s -o /dev/null -w '%{http_code}\n' -H 'Bms-Client-Build: sha-0000000; built=20000101000000' https://<tenant host>/api/v1/me`
   answers `426`.
4. Leave it set. A later release does not need it changed unless it breaks old screens again; then
   raise it to that release's time. Clearing it (empty) serves every build again.
5. **Before a rollback, clear it** (`docs/runbooks/rollback.md` step 5). A minimum later than the
   running API's own build (`BMS_GIT_TIME` in its image) is ignored with a warning in the API log,
   so a rollback with it left in place does not lock phones out, but the setting then means nothing
   until it is cleared or the newer release is back.

The guard compares the minimum with the **API's** build time, which is the web app's too only
because `api` and `web` always deploy from the same tag (ADR-006: build once, one tag for every
image). Never run an api and a web of different releases with `BMS_MIN_CLIENT_BUILD` set. An image
built without `GIT_TIME` (a manual build) cannot guard a rollback, and the API says so with a warning
in its log at start.

Requests without a build time (curl, payment callbacks, apps from before #576) are never refused.
Offline sales queued on a phone stay queued through the update and are sent by the new app.

## Manual deploy (only when the pipeline is unavailable)

Run exactly what the pipeline runs, on the host: as `deploy` in production, as `bms` on staging.

```bash
cd /opt/bms
# production, if the packages are private: a token with read:packages only
echo "$TOKEN" | docker login ghcr.io --username <github-user> --password-stdin
./deploy.sh sha-<7>          # or vX.Y.Z on production
docker logout ghcr.io
```

The files in `/opt/bms` must match the repository at that commit (the production workflow copies
them from `deploy/`; the staging puller installs them from the release's proxy image). Never edit them
on the host. On staging, a manual deploy of a tag other than the pointer's is undone by the next
timer run; stop `bms-pull.timer` first if it must stay.

## Reading the result

- Exit 0: live; `state/current_tag` holds the tag.
- Exit 1 with `failed-signature`: a cosign verification did not pass. Nothing was pulled beyond the
  manifest and nothing changed (ADR-018 finding H2).
- Exit 1 with `refused-downgrade`: the release is older than the one already live and
  `state/allow_downgrade` does not exist (ADR-018 finding L1).
- Exit 1 with `failed-migration` in `state/history.log`: the migration failed and nothing was
  switched. Fix forward with a new migration in a pull request. A `V98:` message about staff
  users sharing a phone is fixed in the data instead: `docs/runbooks/staff-duplicate-phones.md`.
- Exit 1 with `failed-health` then `rolled-back-to`: the new release did not become ready and the
  previous one is serving again. See `docs/runbooks/rollback.md`.
- Exit 2: invalid tag. Exit 3: another deploy holds the lock.

```bash
tail -n 20 /opt/bms/state/history.log
docker compose --project-name bms -f /opt/bms/compose.yml ps
docker compose --project-name bms -f /opt/bms/compose.yml logs --tail 100 api
```
