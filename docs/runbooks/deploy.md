# Runbook: deploy

**Applies to:** staging and production · **Decisions:** ADR-006, ADR-018 · **Detail:** `docs/sdd/10-cicd-pipeline.md`

## Normal path (no host access needed)

| Target | How |
|---|---|
| Staging | Merge a pull request to `main`. `deploy.yml` builds `sha-<7>` and moves the `staging` pointer to it; the staging host's timer notices within two minutes and deploys it (pull model, ADR-018). |
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
  that `docker pull ghcr.io/rincoltech-solutions-ltd/bms-platform-api:staging` works on the host
  (the packages must be public).
- `state/last_failed_tag` holds the tag: that release failed and is not retried. Fix forward with
  a new merge (a new tag is tried at once), or retry the same one with
  `rm /opt/bms/state/last_failed_tag`.
- To pause staging deploys: `systemctl stop bms-pull.timer` (and `start` to resume).

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
them from `deploy/`; the staging puller installs them from the commit's tarball). Never edit them
on the host. On staging, a manual deploy of a tag other than the pointer's is undone by the next
timer run; stop `bms-pull.timer` first if it must stay.

## Reading the result

- Exit 0: live; `state/current_tag` holds the tag.
- Exit 1 with `failed-migration` in `state/history.log`: the migration failed and nothing was
  switched. Fix forward with a new migration in a pull request.
- Exit 1 with `failed-health` then `rolled-back-to`: the new release did not become ready and the
  previous one is serving again. See `docs/runbooks/rollback.md`.
- Exit 2: invalid tag. Exit 3: another deploy holds the lock.

```bash
tail -n 20 /opt/bms/state/history.log
docker compose --project-name bms -f /opt/bms/compose.yml ps
docker compose --project-name bms -f /opt/bms/compose.yml logs --tail 100 api
```
