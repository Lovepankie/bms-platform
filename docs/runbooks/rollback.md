# Runbook: rollback

**Applies to:** staging and production · **Decisions:** ADR-006, ADR-018

## Automatic rollback

`deploy.sh` rolls back by itself when a new release does not pass the readiness gate: it
switches `api`, `web` and `proxy` back to the tag in `state/current_tag` with the same gap-free
switch as a deploy (ADR-083; a new container that never got healthy was already removed, and the
old one never stopped serving), waits for it to be
ready, records `failed-health` and `rolled-back-to` in `state/history.log`, and exits 1. Before
switching back it keeps the failed build's hashed web files in `bms_web_assets`, so a phone that
loaded that build in the meantime still works (ADR-078). Nothing
more is needed except fixing the release. On staging the puller also writes the failed tag to
`state/last_failed_tag`, so it is not tried again every two minutes; the next merge to `main`
moves the pointer to a new tag, which is tried at once.

## Manual rollback (a release is live but wrong)

A rollback is a deploy of the previous tag. Migrations are not reversed: they are expand and
contract (chapter 6 section 6.9), so the previous release runs on the newer schema.

1. Find the last good tag:

   ```bash
   grep deployed /opt/bms/state/history.log | tail -n 5
   ```

2. Preferred, through the pipeline: production, re-run the `Deploy to production` job of the
   last good tag's run (or push a new patch tag on the last good commit); staging, revert the
   bad commit on `main`, which moves the pointer to a new tag that the host deploys.
3. Direct, on the host, when minutes matter. A rollback deploys a release older than the one live,
   which `deploy.sh` otherwise refuses (ADR-018 finding L1), so mark it deliberate first:

   ```bash
   touch /opt/bms/state/allow_downgrade
   cd /opt/bms && ./deploy.sh v1.4.2     # production: the last good tag
   # staging, as bms: stop the timer first, or the next run redeploys the pointer's tag
   systemctl stop bms-pull.timer          # as root
   touch /opt/bms/state/allow_downgrade
   cd /opt/bms && ./deploy.sh sha-<last good 7>
   rm -f /opt/bms/state/allow_downgrade    # back to the normal, fail-closed default
   ```

   The migrate step is a no-op (the schema is already ahead) and the switch happens as usual. On
   staging, start `bms-pull.timer` again once `main` carries the fix or the revert.
4. Confirm `https://<platform host>/version` shows the old tag, then open an issue for the fix.
5. If `BMS_MIN_CLIENT_BUILD` is set in `/opt/bms/.env` to a time later than the release you rolled
   back to (ADR-088), clear it (empty) and recreate the api the usual way. The API already ignores a
   minimum later than its own build, with a warning in its log at start (`BMS_MIN_CLIENT_BUILD ... is
   later than this release`), so phones keep working meanwhile; clearing it removes the warning and
   the doubt.

## When the rollback itself is unhealthy (`rollback-unhealthy`)

The previous release does not start against the current schema, which means a migration was not
backward compatible.

1. Check the API logs: `docker compose --project-name bms -f /opt/bms/compose.yml logs --tail 200 api`.
2. If the cause is a schema change, write a forward-fixing migration and release it; do not edit
   or delete an applied migration.
3. If data is damaged, stop and follow `docs/runbooks/restore-from-backup.md` with the dev lead.
