#!/usr/bin/env bash
# Pull-based deploy for the ARM64 staging host (ADR-018, docs/runbooks/deploy.md). GitHub cannot
# reach this host, so the host asks: bms-pull.timer runs this script every two minutes as the
# unprivileged bms user.
#
#   1. Pull ghcr.io/rincoltech-solutions-ltd/bms-platform-api:staging, the pointer tag CI moves after
#      every green build of main, and read two labels of its image: org.opencontainers.image.version
#      (the immutable sha-<short> tag) and org.opencontainers.image.revision (the full commit).
#      An unchanged pointer is a manifest check only; nothing is downloaded.
#   2. If that tag is already live (state/current_tag), stop. If it failed before
#      (state/last_failed_tag), stop too: a broken release is not retried every two minutes.
#   3. Resolve the api, web and proxy images to their digests and verify each one's cosign
#      signature against /opt/bms/cosign.pub, the key provisioned once by hand
#      (docs/runbooks/provision-host.md), never taken from the release itself (ADR-018 finding H2).
#      Nothing from the release is extracted or run before every signature verifies.
#   4. Copy that release's host files out of its verified proxy image (/usr/share/bms-deploy, built
#      from the repository's deploy/ at the same commit) into releases/<tag>/, never touching the
#      live compose.yml, deploy.sh, backup.sh or pull-staging.sh yet (ADR-018 finding M3).
#   5. Run releases/<tag>/deploy.sh <sha-tag>: migrations before the swap, readiness gate, automatic
#      rollback, all against the shared state/ directory.
#   6. Only on success: point the `current` symlink at this release and refresh the live top-level
#      copies of compose.yml and the host scripts from it. A failed release
#      keeps the previous release live and this script un-replaced; releases/<tag>/ stays for
#      inspection.
#   7. On success, keep the images of this and the previous release, and every image a container
#      uses, and remove older ones (the SD card is small). Under ADR-083 the proxy keeps running an
#      older release's image while its binary and Caddyfiles are unchanged, so that image stays.
#
# Exit status: 0 nothing to do or deployed; non-zero a failed deploy (see state/history.log).
# To retry a failed tag: rm state/last_failed_tag, or run ./deploy.sh <tag> by hand.
set -euo pipefail
exec < /dev/null

# The release images compose.yml runs; the pointer lives in the same repositories unless overridden.
RELEASE_PREFIX="ghcr.io/rincoltech-solutions-ltd/bms-platform"
POINTER_PREFIX="${BMS_POINTER_PREFIX:-$RELEASE_PREFIX}"
POINTER="${BMS_POINTER_TAG:-staging}"
COMPOSE_SOURCE="${BMS_COMPOSE_SOURCE:-compose.pi-staging.yml}"
COSIGN_IMAGE="${BMS_COSIGN_IMAGE:-ghcr.io/sigstore/cosign/cosign:v2.4.1}"

cd "$(dirname "$0")"
ROOT="$PWD"
mkdir -p state releases

log() { echo "[pull $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
write_state() {
    local file="$1" content="$2"
    printf '%s\n' "$content" > "$file.tmp"
    sync "$file.tmp" 2>/dev/null || true
    mv -f "$file.tmp" "$file"
}

# The unit check of step 6 (issue #580): a unit that differs from the installed copy is named in the log. A timer job may
# run from this user's crontab instead (the restore drill, Sunday 03:30, as docs/runbooks/provision-host.md section 8.5
# allows), so a unit that is not installed at all, whose service's ExecStart script a crontab line runs, is the installed
# way and is not named. deploy/tests/cleanup-test.sh runs this function with a stub crontab.
# Copies a release's postgres/initdb into the host's fixed $ROOT/postgres/initdb, the folder compose mounts into
# postgres. It updates the folder in place (never removes and re-creates it) so the mount keeps pointing at it,
# drops files the release no longer has, and leaves everything readable by the postgres uid despite UMask=0077.
sync_initdb() {
    local from="$1/postgres/initdb" to="$ROOT/postgres/initdb" f
    mkdir -p "$to"
    for f in "$to"/* "$to"/.[!.]*; do
        [[ -e "$f" && ! -e "$from/$(basename "$f")" ]] && rm -rf "$f"
    done
    cp -R "$from/." "$to/"
    chmod -R a+rX "$ROOT/postgres"
}

unit_drift_notices() {
    local units="$1" installed="$2" jobs unit name script
    jobs="$(crontab -l 2>/dev/null | grep -v '^[[:space:]]*#' || true)"
    for unit in "$units"/*; do
        name="$(basename "$unit")"
        if cmp -s "$unit" "$installed/$name" 2>/dev/null; then
            continue
        fi
        if [[ ! -e "$installed/$name" ]]; then
            # The script is the first absolute path ending in .sh after the systemd prefixes (- @ + !), so
            # ExecStart=/bin/bash /opt/bms/x.sh names x.sh, not bash; a cron line must have it as one whole field.
            # No service, or no .sh in its ExecStart, leaves it empty: || true, since this script runs under set -e.
            script="$(sed -n 's/^ExecStart=[-@+!]*//p' "$units/${name%.*}.service" 2>/dev/null | head -n 1 \
                | grep -oE '(^|[[:space:]])/[^[:space:]]+\.sh([[:space:]]|$)' | head -n 1 | tr -d '[:space:]' || true)"
            if [[ -n "$script" ]] && awk -v s="$script" '{ for (i = 1; i <= NF; i++) if ($i == s) found = 1 } END { exit !found }' <<< "$jobs"; then
                continue
            fi
        fi
        log "notice: systemd/${name} differs from the installed unit; copy it by hand and run 'systemctl daemon-reload' (docs/runbooks/provision-host.md)"
    done
}

# Step 7 (issue #580): remove the release images other than the two kept tags. An image any container uses, running or
# stopped, is kept whatever its tag: under ADR-083 the proxy keeps an older release's image while what it runs is
# unchanged, and Docker refuses to remove an image a container uses. Compose runs images by digest, so the match is on
# the image ID, not the name. deploy/tests/cleanup-test.sh runs this function against a stub docker.
remove_old_images() {
    local keep_tag="$1" keep_previous="$2" in_use ref id
    # A container removed between ps and inspect only drops out of the list. If the list comes back empty, Docker's own
    # refusal to remove an image in use is the backstop, and the caller ignores this function's status.
    in_use="$(docker ps --all --quiet | xargs -r docker inspect --format '{{.Image}}' 2>/dev/null || true)"
    docker image ls --no-trunc --format '{{.Repository}}:{{.Tag}} {{.ID}}' \
        | grep -E "^${RELEASE_PREFIX//./\\.}-(api|web|proxy):sha-[0-9a-f]+ " \
        | while read -r ref id; do
            if [[ "${ref##*:}" == "$keep_tag" || "${ref##*:}" == "$keep_previous" ]]; then
                continue
            fi
            if grep -qxF -- "$id" <<< "$in_use"; then
                log "keeping ${ref}: a container uses it"
                continue
            fi
            docker image rm "$ref" > /dev/null || true
        done
}

# One run at a time; a run that finds the previous one still deploying just ends.
exec 8> state/pull.lock
if ! flock --nonblock 8; then
    log "previous run still in progress"
    exit 0
fi

pointer_image="${POINTER_PREFIX}-api:${POINTER}"
docker pull --quiet "$pointer_image" > /dev/null
label() { docker image inspect --format "{{ index .Config.Labels \"$1\" }}" "$pointer_image"; }
tag="$(label org.opencontainers.image.version)"
revision="$(label org.opencontainers.image.revision)"

if [[ ! "$revision" =~ ^[0-9a-f]{40}$ || "$tag" != "sha-${revision:0:7}" ]]; then
    log "pointer ${pointer_image} has unexpected labels (version '${tag}', revision '${revision}')"
    exit 1
fi

current="$(cat state/current_tag 2>/dev/null || true)"
if [[ "$tag" == "$current" ]]; then
    exit 0
fi
if [[ "$tag" == "$(cat state/last_failed_tag 2>/dev/null || true)" ]]; then
    exit 0
fi
log "pointer ${POINTER} is ${tag} (live: ${current:-none}); verifying ${revision}"

# ADR-018 finding H2: verify before trusting anything from this release. The key is this host's own
# fixed copy, never the release's; docker create/docker cp below never runs before every signature
# verifies.
resolve_digest() {
    local tag_ref="$1"
    docker pull --quiet "$tag_ref" > /dev/null
    docker image inspect --format '{{ index .RepoDigests 0 }}' "$tag_ref"
}
# The cosign image runs as uid 65532 and cannot read this user's private Docker login, so give it a
# readable copy of the registry credentials. It lives under $ROOT, which only this user can enter.
install -d -m 755 "$ROOT/dockercfg"
install -m 644 "${DOCKER_CONFIG:-$HOME/.docker}/config.json" "$ROOT/dockercfg/config.json"
verify_signature() {
    local digest_ref="$1"
    docker run --rm \
        --volume "$ROOT/cosign.pub:/cosign.pub:ro" \
        --volume "$ROOT/dockercfg:/home/nonroot/.docker:ro" \
        --env DOCKER_CONFIG=/home/nonroot/.docker \
        "$COSIGN_IMAGE" verify --key /cosign.pub --insecure-ignore-tlog=true "$digest_ref" \
        > /dev/null 2>&1
}
if [[ ! -s "$ROOT/cosign.pub" ]]; then
    log "no cosign.pub provisioned on this host; refusing to trust any release (docs/runbooks/provision-host.md)"
    exit 1
fi
api_digest="$(resolve_digest "${RELEASE_PREFIX}-api:${tag}")"
web_digest="$(resolve_digest "${RELEASE_PREFIX}-web:${tag}")"
proxy_digest="$(resolve_digest "${RELEASE_PREFIX}-proxy:${tag}")"
for ref in "$api_digest" "$web_digest" "$proxy_digest"; do
    if ! verify_signature "$ref"; then
        log "cosign signature did not verify for ${ref}; refusing this release"
        write_state "state/last_failed_tag" "$tag"
        exit 1
    fi
done
log "signatures verified; installing host files of ${revision} into releases/${tag}"

release_dir="$ROOT/releases/$tag"
rm -rf "$release_dir"
mkdir -p "$release_dir"
work="$(mktemp -d)"
container="$(docker create "$proxy_digest")"
trap 'docker rm "$container" > /dev/null 2>&1 || true; rm -rf "$work"' EXIT
docker cp "$container:/usr/share/bms-deploy" "$work/deploy" > /dev/null
src="$work/deploy"

install -m 644 "$src/$COMPOSE_SOURCE" "$release_dir/compose.yml"
for script in deploy.sh backup.sh restore-drill.sh monitor.sh pull-staging.sh; do
    install -m 755 "$src/$script" "$release_dir/$script"
done
for dir in postgres sql systemd; do
    cp -R "$src/$dir" "$release_dir/$dir"
done
# The unit runs with UMask=0077, but the postgres container (another uid) must read initdb/ and sql/.
# Both hold no secrets, only scripts and SQL.
chmod -R a+rX "$release_dir/postgres" "$release_dir/sql"
# postgres mounts the host's fixed postgres/initdb (compose.pi-staging.yml), which is refreshed only after a deploy
# succeeds. On a new host it is missing, or empty when a compose command ran first (Docker creates a missing bind
# source as an empty folder), so seed it from this release before the first deploy. The seed comes from a release
# that has not deployed yet; if that deploy fails the seed stays until the next good deploy refreshes it.
if [[ -z "$(ls -A "$ROOT/postgres/initdb" 2> /dev/null)" ]]; then
    sync_initdb "$release_dir"
fi

status=0
BMS_HOST_ROOT="$ROOT" DOCKER_CONFIG="$ROOT/dockercfg" BMS_STATE_DIR="$ROOT/state" BMS_COSIGN_PUBKEY="$ROOT/cosign.pub" BMS_ENV_FILE="$ROOT/.env" \
    BMS_EDGE_SERVICES="cloudflared" \
    "$release_dir/deploy.sh" "$tag" || status=$?
case "$status" in
    0)
        rm -f state/last_failed_tag
        # Only now does the host's live path start matching this release (ADR-018 finding M3):
        # systemd, cron and the runbooks all invoke the fixed top-level names, so a failed release
        # never displaces what is actually running or this script's own next run.
        ln -sfn "releases/$tag" current
        install -m 644 "$release_dir/compose.yml" compose.yml.new && mv -f compose.yml.new compose.yml
        for script in deploy.sh backup.sh restore-drill.sh monitor.sh pull-staging.sh; do
            install -m 755 "$release_dir/$script" "$script.new" && mv -f "$script.new" "$script"
        done
        for dir in sql systemd; do
            rm -rf "$dir" && cp -R "$release_dir/$dir" "$dir"
        done
        # postgres/initdb is bind-mounted into the running postgres, so it is updated in place, never removed.
        rm -rf postgres/recommended && mkdir -p postgres && cp -R "$release_dir/postgres/recommended" postgres/recommended
        sync_initdb "$release_dir"
        # ADR-018 finding L2: this user has no privilege to install unit files itself, so at least
        # make drift visible in this job's own log instead of silent (docs/runbooks/provision-host.md
        # section 8.4 still requires a human to copy a changed unit and run daemon-reload).
        unit_drift_notices systemd /etc/systemd/system
        ;;
    3)
        log "another deploy holds the lock; trying again on the next run"
        rm -rf "$release_dir"
        exit 0
        ;;
    *)
        write_state "state/last_failed_tag" "$tag"
        log "deploy of ${tag} failed (exit ${status}); releases/${tag} kept for inspection"
        log "not retried until state/last_failed_tag is removed"
        exit "$status"
        ;;
esac

log "removing release images other than ${tag}, ${current:-none} and those a container uses"
remove_old_images "$tag" "$current" || true
docker image prune --force > /dev/null || true

log "removing releases/ directories other than ${tag} and ${current:-none}"
find releases -mindepth 1 -maxdepth 1 -type d | while read -r dir; do
    name="$(basename "$dir")"
    if [[ "$name" != "$tag" && "$name" != "$current" ]]; then
        rm -rf "$dir"
    fi
done || true
