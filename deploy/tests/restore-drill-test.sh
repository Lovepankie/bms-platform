#!/usr/bin/env bash
# The weekly restore drill, run with no argument as bms-restore-drill.service and the staging cron line run it
# (/opt/bms/restore-drill.sh), is unchanged by the rehearsal mode of issue #138: same steps, same docker calls, same log
# lines, same state files, same exit codes and the same clean-up, on a fake host (deploy/tests/drill-fakes.sh) with no
# network, no Docker and no PostgreSQL.
#
# deploy/tests/golden/restore-drill-no-args.txt is the transcript of the drill as it was before the rehearsal mode (main
# at 0da2f17), made with UPDATE_GOLDEN=1 against that script: a success on staging and on production, a failed
# decryption and a failed pg_restore. The current script must print the same transcript byte for byte, its PID aside.
# Run: bash deploy/tests/restore-drill-test.sh
set -uo pipefail

TESTS_HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=deploy/tests/drill-fakes.sh
. "$TESTS_HERE/drill-fakes.sh"
GOLDEN="$TESTS_HERE/golden/restore-drill-no-args.txt"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# transcript <name>: runs the drill with no argument on a new fake host and prints what it did.
transcript() {
    local name="$1" f
    fake_host "$WORK/$name"
    if [[ -n "${PRODUCTION:-}" ]]; then
        sed -i 's/^BMS_ENVIRONMENT=staging$/BMS_ENVIRONMENT=production/' "$HOST/.env"
    fi
    # shellcheck disable=SC2119 # the drill is run with no argument on purpose.
    run_script
    {
        echo "== ${name}"
        echo "exit ${STATUS}"
        echo "-- output"
        printf '%s\n' "$OUTPUT"
        echo "-- docker calls"
        cat "$FAKE/calls.log"
        echo "-- state files"
        while IFS= read -r f; do
            printf '%s: %s\n' "$f" "$(tr '\n' '~' < "$HOST/$f")"
        done < <(cd "$HOST" && find state -type f | LC_ALL=C sort)
        echo "-- left in backups/"
        (cd "$HOST/backups" && find . -mindepth 1 | LC_ALL=C sort)
        echo "-- containers left"
        cat "$FAKE/containers"
    } | sed "s|$WORK|WORK|g" | normalise
}

actual="$WORK/actual.txt"
{
    transcript ok-staging
    PRODUCTION=1 transcript ok-production
    FAKE_DECRYPT_FAIL=1 transcript decryption-fails
    FAKE_RESTORE_FAIL=1 transcript pg-restore-fails
} > "$actual"

if [[ -n "${UPDATE_GOLDEN:-}" ]]; then
    mkdir -p "$(dirname "$GOLDEN")"
    cp "$actual" "$GOLDEN"
    echo "wrote $GOLDEN"
    exit 0
fi

failures=0
if diff -u "$GOLDEN" "$actual"; then
    echo "ok   the drill with no argument does what it did before the rehearsal mode, step for step"
else
    echo "FAIL the drill with no argument changed (diff above: - before, + now)"
    failures=1
fi
# The transcript itself must show the drill working, so a golden made from a broken stub cannot pass.
if grep -q '^exit 0$' "$GOLDEN" && grep -q 'OK bms-staging-20261024T011500Z.dump.enc' "$GOLDEN" \
    && [[ "$(grep -c '^exit 1$' "$GOLDEN")" == 2 ]]; then
    echo "ok   the golden transcript holds two successes and two failures"
else
    echo "FAIL the golden transcript is not the drill's two successes and two failures"
    failures=1
fi
if grep -q 'rehears' "$actual"; then
    echo "FAIL the drill with no argument mentions the rehearsal"
    failures=1
else
    echo "ok   the drill with no argument never touches the rehearsal"
fi
exit "$failures"
