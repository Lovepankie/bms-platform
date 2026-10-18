#!/bin/bash
# Installs the BMS deploy runner in the WSL Ubuntu of Jerome's laptop (ADR-040, amendment 2026-10-18: the Pi hard-resets
# under heavy work, so builds moved here). Run as root inside WSL, once, and again after any change to the files next
# to it (idempotent).
#
# It adds ONE new user `bmsdeploy` (uid 1006) next to the existing runner users (ghrunner .. ghrunner5, AGA and others)
# and touches none of them: its own wall, caps, rootless Docker, hooks and runner. The Pi files (provision.sh and its
# siblings) are the source; this script derives the laptop variant from them (user id, DNS rule from the host's
# resolver as the other users' walls do, x64 runner build, caps sized for 19 GB).
#
#   sudo RUNNER_TOKEN=<registration token> ./provision-laptop.sh
#
# Registered in the organisation runner group `bms-deploy` (visible to bms-platform only, restricted to deploy.yml at
# refs/heads/main). Token: gh api -X POST orgs/RincolTech-Solutions-ltd/actions/runners/registration-token --jq .token
set -euo pipefail

ORG="RincolTech-Solutions-ltd"
RUNNER_GROUP="bms-deploy"
RUNNER_NAME="bms-deploy-laptop"
LABELS="bms-deploy"
USER_NAME="bmsdeploy"
USER_UID=1006
SUB_START=427680
HOME_DIR="/home/${USER_NAME}"
RUNNER_DIR="${HOME_DIR}/actions-runner"
HOOK_DIR="/usr/local/lib/bms-deploy"
RUNNER_VERSION="${RUNNER_VERSION:-2.338.0}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[ "$(id -u)" = "0" ] || { echo "run as root" >&2; exit 1; }
[ "$(uname -m)" = "x86_64" ] || { echo "this script is for the x86_64 laptop" >&2; exit 1; }
grep -qi microsoft /proc/version || { echo "this script is for WSL" >&2; exit 1; }

echo "== user ${USER_NAME} (uid ${USER_UID}), no sudo, no docker group, locked password"
if ! id "${USER_NAME}" >/dev/null 2>&1; then
  if getent passwd "${USER_UID}" >/dev/null; then echo "uid ${USER_UID} is taken by another user" >&2; exit 1; fi
  useradd --create-home --uid "${USER_UID}" --shell /bin/bash "${USER_NAME}"
fi
[ "$(id -u "${USER_NAME}")" = "${USER_UID}" ] || { echo "${USER_NAME} must have uid ${USER_UID}: the wall matches it" >&2; exit 1; }
passwd -l "${USER_NAME}" >/dev/null
for forbidden in sudo docker dialout adm; do
  if id -nG "${USER_NAME}" | tr ' ' '\n' | grep -qx "${forbidden}"; then echo "${USER_NAME} must not be in group ${forbidden}" >&2; exit 1; fi
done
grep -q "^${USER_NAME}:" /etc/subuid || echo "${USER_NAME}:${SUB_START}:65536" >> /etc/subuid
grep -q "^${USER_NAME}:" /etc/subgid || echo "${USER_NAME}:${SUB_START}:65536" >> /etc/subgid
loginctl enable-linger "${USER_NAME}"
grep -qx "${USER_NAME}" /etc/cron.deny 2>/dev/null || echo "${USER_NAME}" >> /etc/cron.deny

echo "== derive the laptop files from the Pi files"
T="$(mktemp -d)"; trap 'rm -rf "${T}"' EXIT
for f in bmsdeploy-netwall.service 50-ci-walls.conf 50-ci-caps.conf bmsdeploy-actions-runner.service job-started.sh job-completed.sh; do
  sed "s/1002/${USER_UID}/g" "${HERE}/${f}" > "${T}/${f}"
done
sed -i 's/MemoryMax=3500M/MemoryMax=5G/; s/CPUQuota=300%/CPUQuota=400%/' "${T}/50-ci-caps.conf"
sed -i 's/bms-deploy-pi/bms-deploy-laptop/g; s/ (Pi) or `bms-deploy-laptop` (laptop)//' "${T}/bmsdeploy-actions-runner.service"
sed -i '/^ExecStart=/i ExecStartPre=/usr/local/lib/bms-deploy/gen-wall.sh' "${T}/bmsdeploy-netwall.service"
# The wall: the Pi's file with the DNS rule taken from the host's own resolver at every boot, as the other users' walls do.
sed -e "s/1002/${USER_UID}/g" \
    -e 's#ip daddr 192.168.1.1 meta l4proto { tcp, udp } th dport 53#ip daddr { @@NS@@ } meta l4proto { tcp, udp } th dport 53#' \
    "${HERE}/bmsdeploy-wall.nft" > "${T}/bmsdeploy-wall.nft.in"
grep -q '@@NS@@' "${T}/bmsdeploy-wall.nft.in" || { echo "the DNS rule was not found in the wall file" >&2; exit 1; }
cat > "${T}/gen-wall.sh" <<'GEN'
#!/bin/bash
set -euo pipefail
ns=$(awk '/^nameserver/ && $2 ~ /^[0-9.]+$/ {print $2}' /etc/resolv.conf | paste -sd, -)
[ -n "$ns" ] || ns=127.0.0.53
install -d -m 755 /etc/nftables.d
sed "s#@@NS@@#$ns#" /usr/local/lib/bms-deploy/bmsdeploy-wall.nft.in > /etc/nftables.d/bmsdeploy-wall.nft
GEN

echo "== wall, caps, units, hooks"
install -d -m 755 "${HOOK_DIR}" /etc/nftables.d "/etc/systemd/system/user@${USER_UID}.service.d" "/etc/systemd/system/user-${USER_UID}.slice.d"
CHANGED=0
put() {  # put <mode> <source> <destination>
  if ! cmp -s "$2" "$3" 2>/dev/null; then CHANGED=1; fi
  install -m "$1" "$2" "$3"
}
WALLS_BEFORE="$(cat "/etc/systemd/system/user@${USER_UID}.service.d/50-ci-walls.conf" 2>/dev/null || true)"
put 644 "${T}/bmsdeploy-wall.nft.in" "${HOOK_DIR}/bmsdeploy-wall.nft.in"
put 755 "${T}/gen-wall.sh" "${HOOK_DIR}/gen-wall.sh"
put 755 "${T}/job-started.sh" "${HOOK_DIR}/job-started.sh"
put 755 "${T}/job-completed.sh" "${HOOK_DIR}/job-completed.sh"
put 644 "${T}/bmsdeploy-netwall.service" /etc/systemd/system/bmsdeploy-netwall.service
put 644 "${T}/50-ci-walls.conf" "/etc/systemd/system/user@${USER_UID}.service.d/50-ci-walls.conf"
put 644 "${T}/50-ci-caps.conf" "/etc/systemd/system/user-${USER_UID}.slice.d/50-ci-caps.conf"
put 644 "${T}/bmsdeploy-actions-runner.service" /etc/systemd/system/bmsdeploy-actions-runner.service
chown -R root:root "${HOOK_DIR}"
"${HOOK_DIR}/gen-wall.sh"
nft -c -f /etc/nftables.d/bmsdeploy-wall.nft
systemctl daemon-reload
systemctl enable bmsdeploy-netwall.service
systemctl restart bmsdeploy-netwall.service
systemctl start "user@${USER_UID}.service"
if [ "${WALLS_BEFORE}" != "$(cat "/etc/systemd/system/user@${USER_UID}.service.d/50-ci-walls.conf")" ]; then systemctl restart "user@${USER_UID}.service"; fi

echo "== rootless Docker for ${USER_NAME}"
RL_ENV=("XDG_RUNTIME_DIR=/run/user/${USER_UID}" "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/${USER_UID}/bus")
if [ ! -f "${HOME_DIR}/.config/systemd/user/docker.service" ]; then
  runuser -u "${USER_NAME}" -- env "${RL_ENV[@]}" PATH="/usr/bin:/usr/sbin:/bin" /usr/bin/dockerd-rootless-setuptool.sh install || true
fi
runuser -u "${USER_NAME}" -- env "${RL_ENV[@]}" systemctl --user enable --now docker.service
runuser -u "${USER_NAME}" -- env "${RL_ENV[@]}" systemctl --user is-active --quiet docker.service \
  || { echo "the rootless Docker daemon of ${USER_NAME} is not running" >&2; exit 1; }
runuser -u "${USER_NAME}" -- env "${RL_ENV[@]}" DOCKER_HOST="unix:///run/user/${USER_UID}/docker.sock" docker version --format '{{.Server.Version}}' \
  || { echo "docker does not answer for ${USER_NAME}" >&2; exit 1; }
chown -R root:"${USER_NAME}" "${HOME_DIR}/.config/systemd/user"
find "${HOME_DIR}/.config/systemd/user" -type d -exec chmod 755 {} \;
find "${HOME_DIR}/.config/systemd/user" -type f -exec chmod 644 {} \;

echo "== runner ${RUNNER_VERSION} (x64)"
if [ ! -f "${RUNNER_DIR}/.runner" ]; then
  : "${RUNNER_TOKEN:?first install needs RUNNER_TOKEN (see the header of this file)}"
  asset="actions-runner-linux-x64-${RUNNER_VERSION}.tar.gz"
  work="$(mktemp -d)"; chmod 755 "${work}"
  trap 'rm -rf "${T}" "${work}"' EXIT
  curl -fsSL -o "${work}/${asset}" "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${asset}"
  chmod 644 "${work}/${asset}"
  want="$(curl -fsSL "https://api.github.com/repos/actions/runner/releases/tags/v${RUNNER_VERSION}" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print([a['digest'] for a in d['assets'] if a['name']=='${asset}'][0].split(':',1)[1])")"
  have="$(sha256sum "${work}/${asset}" | cut -d' ' -f1)"
  [ "${want}" = "${have}" ] || { echo "runner archive digest mismatch: refusing" >&2; exit 1; }
  install -d -o "${USER_NAME}" -g "${USER_NAME}" -m 755 "${RUNNER_DIR}"
  runuser -u "${USER_NAME}" -- tar -xzf "${work}/${asset}" -C "${RUNNER_DIR}"
  runuser -u "${USER_NAME}" -- env "${RL_ENV[@]}" "${RUNNER_DIR}/config.sh" --unattended --replace \
    --url "https://github.com/${ORG}" --token "${RUNNER_TOKEN}" --runnergroup "${RUNNER_GROUP}" \
    --name "${RUNNER_NAME}" --labels "${LABELS}" --work _work --disableupdate
fi

echo "== lock the runner install (root-owned, read-only to ${USER_NAME}); _work and _diag stay writable"
install -d -o "${USER_NAME}" -g "${USER_NAME}" -m 700 "${RUNNER_DIR}/_work"
install -d -o "${USER_NAME}" -g "${USER_NAME}" -m 775 "${RUNNER_DIR}/_diag"
find "${RUNNER_DIR}" \( -path "${RUNNER_DIR}/_work" -o -path "${RUNNER_DIR}/_diag" \) -prune -o -print0 | xargs -0 chown root:"${USER_NAME}"
find "${RUNNER_DIR}" \( -path "${RUNNER_DIR}/_work" -o -path "${RUNNER_DIR}/_diag" \) -prune -o -type d -print0 | xargs -0 chmod 750
find "${RUNNER_DIR}" \( -path "${RUNNER_DIR}/_work" -o -path "${RUNNER_DIR}/_diag" \) -prune -o -type f -perm -u+x -print0 | xargs -0 -r chmod 750
find "${RUNNER_DIR}" \( -path "${RUNNER_DIR}/_work" -o -path "${RUNNER_DIR}/_diag" \) -prune -o -type f ! -perm -u+x -print0 | xargs -0 -r chmod 640
printf 'LANG=C.UTF-8\nDOCKER_HOST=unix:///run/user/%s/docker.sock\nXDG_RUNTIME_DIR=/run/user/%s\n' "${USER_UID}" "${USER_UID}" > "${RUNNER_DIR}/.env"
chown root:"${USER_NAME}" "${RUNNER_DIR}/.env"; chmod 640 "${RUNNER_DIR}/.env"

echo "== start"
systemctl enable bmsdeploy-actions-runner.service
if [ "${CHANGED}" = "1" ]; then systemctl restart bmsdeploy-actions-runner.service; else systemctl start bmsdeploy-actions-runner.service; fi
sleep 5
systemctl --no-pager --lines=0 status bmsdeploy-actions-runner.service | head -5 || true
echo "done: the runner ${RUNNER_NAME} should show as online in the runner group ${RUNNER_GROUP} of the organisation"
