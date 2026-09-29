# Runbook: rollback

**Applies to:** staging and production · **Decision:** ADR-006

## Automatic rollback

`deploy.sh` rolls back by itself when a new release does not pass the readiness gate: it
switches `api`, `web` and `proxy` back to the tag in `state/current_tag`, waits for it to be
ready, records `failed-health` and `rolled-back-to` in `state/history.log`, and exits 1. Nothing
more is needed except fixing the release.

## Manual rollback (a release is live but wrong)

A rollback is a deploy of the previous tag. Migrations are not reversed: they are expand and
contract (chapter 6 section 6.9), so the previous release runs on the newer schema.

1. Find the last good tag:

   ```bash
   grep deployed /opt/bms/state/history.log | tail -n 5
   ```

2. Preferred, through the pipeline: production, re-run the `Deploy to production` job of the
   last good tag's run (or push a new patch tag on the last good commit); staging, revert the
   bad commit on `main`.
3. Direct, on the host, when minutes matter:

   ```bash
   cd /opt/bms && ./deploy.sh v1.4.2     # the last good tag
   ```

   The migrate step is a no-op (the schema is already ahead) and the switch happens as usual.
4. Confirm `https://app.<base>/version` shows the old tag, then open an issue for the fix.

## When the rollback itself is unhealthy (`rollback-unhealthy`)

The previous release does not start against the current schema, which means a migration was not
backward compatible.

1. Check the API logs: `docker compose --project-name bms -f /opt/bms/compose.yml logs --tail 200 api`.
2. If the cause is a schema change, write a forward-fixing migration and release it; do not edit
   or delete an applied migration.
3. If data is damaged, stop and follow `docs/runbooks/restore-from-backup.md` with the dev lead.
