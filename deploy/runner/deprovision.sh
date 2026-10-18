#!/bin/bash
# Removes the BMS deploy runner from the staging host (ADR-040): the exact inverse of provision.sh.
# Run as root. It never touches `ghrunner`, the bms staging stack or the OpenEMS edge.
#
#   sudo REMOVE_TOKEN=<removal token> ./deprovision.sh
#
# The removal token comes from:
#   gh api -X POST orgs/RincolTech-Solutions-ltd/actions/runners/remove-token --jq .token
# Without it the runner is only stopped and the registration is removed in GitHub's runner list by hand.
set -euo pipefail
USER_NAME="bmsdeploy"
RUNNER_DIR="/home/${USER_NAME}/actions-runner"
[ "$(id -u)" = "0" ] || { echo "run as root" >&2; exit 1; }

systemctl disable --now bmsdeploy-actions-runner.service 2>/dev/null || true
if [ -n "${REMOVE_TOKEN:-}" ] && [ -x "${RUNNER_DIR}/config.sh" ]; then
  runuser -u "${USER_NAME}" -- "${RUNNER_DIR}/config.sh" remove --token "${REMOVE_TOKEN}" || true
fi
systemctl stop "user@1002.service" 2>/dev/null || true
systemctl disable --now bmsdeploy-netwall.service 2>/dev/null || true
rm -f /etc/systemd/system/bmsdeploy-actions-runner.service /etc/systemd/system/bmsdeploy-netwall.service \
      /etc/nftables.d/bmsdeploy-wall.nft \
      /etc/systemd/system/user@1002.service.d/50-ci-walls.conf /etc/systemd/system/user-1002.slice.d/50-ci-caps.conf \
      /etc/systemd/system/user.slice.d/50-user-slice-cap.conf
rmdir /etc/systemd/system/user@1002.service.d /etc/systemd/system/user-1002.slice.d /etc/systemd/system/user.slice.d 2>/dev/null || true
rm -rf /usr/local/lib/bms-deploy
systemctl daemon-reload
loginctl disable-linger "${USER_NAME}" 2>/dev/null || true
if id "${USER_NAME}" >/dev/null 2>&1; then userdel --remove "${USER_NAME}"; fi
sed -i "/^${USER_NAME}\$/d" /etc/cron.deny 2>/dev/null || true
if id "${USER_NAME}" >/dev/null 2>&1; then echo "the user ${USER_NAME} is still there" >&2; exit 1; fi
echo "removed: the runner, its wall and cap, its hooks and the user"
