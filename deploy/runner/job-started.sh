#!/bin/bash
# /usr/local/lib/bms-deploy/job-started.sh  (BMS ADR-040)
#
# ACTIONS_RUNNER_HOOK_JOB_STARTED for bms-deploy-pi. Root-owned 0755 in a root-owned 0755 directory and
# wired by Environment= in the root-owned unit, so a job cannot edit or disable it. Runs as bmsdeploy.
#
# Any job of this repository that targets the `bms-deploy` label can run here, a pull request job
# included. The deploy build must never inherit anything such a job left behind: not a poisoned base
# image, a build cache layer or a credential file. So before every job ALL rootless
# Docker state is removed (containers, images, volumes, build cache), and so are the home-directory files
# a tool would read credentials or settings from. The cost is a cold build every time; that is the price.
set -u
log() { logger -t bms-deploy-hook -- "$*"; }
log "job-started: repo=${GITHUB_REPOSITORY:-unknown} event=${GITHUB_EVENT_NAME:-unknown} ref=${GITHUB_REF:-unknown}"

export DOCKER_HOST="${DOCKER_HOST:-unix:///run/user/1002/docker.sock}"
HOME_BD=/home/bmsdeploy

# Second line behind the organisation runner group (which already admits only deploy.yml on main): refuse any
# job that is not this repository's deploy workflow at refs/heads/main on a push. A non-zero exit fails the
# job before any step runs. Unset variables count as a refusal: if the hook cannot see what is running, nothing
# runs.
if [ "${GITHUB_WORKFLOW_REF:-}" != "RincolTech-Solutions-ltd/bms-platform/.github/workflows/deploy.yml@refs/heads/main" ] \
   || [ "${GITHUB_EVENT_NAME:-}" != "push" ]; then
  log "REFUSED: workflow ref '${GITHUB_WORKFLOW_REF:-unset}' event '${GITHUB_EVENT_NAME:-unset}'"
  echo "bms-deploy-pi runs only deploy.yml on main (ADR-040)" >&2
  exit 1
fi

if docker info >/dev/null 2>&1; then
  cids="$(docker ps -aq 2>/dev/null || true)"
  # shellcheck disable=SC2086
  [ -n "$cids" ] && docker rm -f $cids >/dev/null 2>&1
  docker buildx prune -af >/dev/null 2>&1
  docker system prune -af --volumes >/dev/null 2>&1
  docker network prune -f >/dev/null 2>&1
  log "docker: containers, images, volumes and build cache removed"
else
  log "docker: not reachable, skipped"
fi

rm -rf "$HOME_BD/.ssh" "$HOME_BD/.docker" "$HOME_BD/.gitconfig" "$HOME_BD/.npmrc" "$HOME_BD/.config/git" \
       "$HOME_BD/.config/docker" "$HOME_BD/.cosign" "$HOME_BD/.cache" "$HOME_BD/.m2" "$HOME_BD/.npm" \
       "$HOME_BD/.local/bin" "$HOME_BD/.local/lib" "$HOME_BD/.local/state" "$HOME_BD/.local/share/containers" 2>/dev/null
find /tmp /var/tmp -mindepth 1 -maxdepth 1 -user bmsdeploy -exec rm -rf {} + 2>/dev/null
log "reset: removed credential and tool-cache directories"
log "job-started: done"
exit 0
