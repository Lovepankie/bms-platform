# Runbook: deploy

**Applies to:** staging and production · **Decision:** ADR-006 · **Detail:** `docs/sdd/10-cicd-pipeline.md`

## Normal path (no host access needed)

| Target | How |
|---|---|
| Staging | Merge a pull request to `main`. `deploy.yml` builds `sha-<7>` and deploys it. |
| Production | On a commit already green on staging: `git tag vX.Y.Z <commit>` then `git push origin vX.Y.Z`. Approve the `production` deployment in the Actions run when asked. |

Then check:

1. The Actions run is green. A notice "not provisioned" means the environment's secrets are
   not set; images were still pushed.
2. `curl -s https://app.<base>/version` shows the expected `git_sha` (`sha-<7>`).
3. `curl -s https://app.<base>/readyz` returns `{"status":"UP"}`.
4. For production, announce the release to the team.

## Manual deploy (only when Actions is unavailable)

Run exactly what the pipeline runs, as the `deploy` user on the host:

```bash
cd /opt/bms
# registry login, if the packages are private: a token with read:packages only
echo "$TOKEN" | docker login ghcr.io --username <github-user> --password-stdin
./deploy.sh sha-<7>          # or vX.Y.Z on production
docker logout ghcr.io
```

The files in `/opt/bms` must match the repository at that commit (the pipeline copies them
from `deploy/` on every deploy). Never edit them on the host.

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
