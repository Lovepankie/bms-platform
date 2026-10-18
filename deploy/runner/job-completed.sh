#!/bin/bash
# /usr/local/lib/bms-deploy/job-completed.sh  (BMS ADR-040)
#
# ACTIONS_RUNNER_HOOK_JOB_COMPLETED for the BMS deploy runner: the same wipe as job-started.sh, run again at the
# end so a registry credential written by docker/login-action does not sit on disk until the next job,
# plus the downloaded tool and action caches of the job's work area. The job's own _temp is left for the
# runner to clean. Always exits 0: a failed cleanup must not turn a green build red.
set -u
log() { logger -t bms-deploy-hook -- "$*"; }
export DOCKER_HOST="${DOCKER_HOST:-unix:///run/user/$(id -u)/docker.sock}"
HOME_BD="${HOME:-/home/bmsdeploy}"
log "job-completed: repo=${GITHUB_REPOSITORY:-unknown} event=${GITHUB_EVENT_NAME:-unknown}"

if docker info >/dev/null 2>&1; then
  cids="$(docker ps -aq 2>/dev/null || true)"
  # shellcheck disable=SC2086
  [ -n "$cids" ] && docker rm -f $cids >/dev/null 2>&1
  docker buildx prune -af >/dev/null 2>&1
  docker system prune -af --volumes >/dev/null 2>&1
fi
rm -rf "$HOME_BD/.docker" "$HOME_BD/.ssh" "$HOME_BD/.gitconfig" "$HOME_BD/.npmrc" "$HOME_BD/.config/git" \
       "$HOME_BD/.config/docker" "$HOME_BD/.cosign" "$HOME_BD/.cache" "$HOME_BD/.m2" "$HOME_BD/.npm" \
       "$HOME_BD/.local/bin" "$HOME_BD/.local/lib" "$HOME_BD/.local/state" 2>/dev/null
find /tmp /var/tmp -mindepth 1 -maxdepth 1 -user bmsdeploy -exec rm -rf {} + 2>/dev/null
WORK="$HOME_BD/actions-runner/_work"
if [ -d "$WORK" ]; then
  find "$WORK" -mindepth 1 -maxdepth 1 ! -name _temp -exec rm -rf {} + 2>/dev/null
fi
log "job-completed: done"
exit 0
