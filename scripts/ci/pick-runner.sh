#!/bin/bash
# Chooses where the heavy CI jobs run (ADR-018 amendment 2026-10-16).
# Rule (Hillary): the three runners on Jerome's laptop first; the Pi only when all three are busy
# or offline. Workflows read the repository variable CI_RUNS_ON when a run is created
# (`runs-on: ${{ fromJSON(vars.CI_RUNS_ON || '["self-hosted","jerome-laptop"]') }}`); this script sets it.
# Runs every minute from a launchd job on the maintainer's Mac (needs `gh` signed in as an org admin).
# No secret is read or written; it only reads runner busy/online flags and writes one variable.
set -u
ORG="RincolTech-Solutions-ltd"; REPO="$ORG/bms-platform"
LAPTOP='["self-hosted","jerome-laptop"]'; PI='["self-hosted","hillary-pi"]'
json=$(gh api "orgs/$ORG/actions/runners" --paginate 2>/dev/null) || exit 0
laptop_idle=$(echo "$json" | jq '[.runners[]|select(.labels[].name=="jerome-laptop" and .status=="online" and .busy==false)]|length')
laptop_online=$(echo "$json" | jq '[.runners[]|select(.labels[].name=="jerome-laptop" and .status=="online")]|length')
pi_idle=$(echo "$json" | jq '[.runners[]|select(.labels[].name=="hillary-pi" and .status=="online" and .busy==false)]|length')
if [ "$laptop_idle" -ge 1 ]; then want="$LAPTOP"
elif [ "$laptop_online" -eq 0 ] && [ "$pi_idle" -ge 0 ]; then want="$PI"   # laptop offline: Pi
elif [ "$pi_idle" -ge 1 ]; then want="$PI"                                  # all three laptop runners busy, Pi free
else want="$LAPTOP"; fi                                                      # everything busy: queue on the laptop
cur=$(gh variable get CI_RUNS_ON -R "$REPO" 2>/dev/null || echo "")
if [ "$cur" != "$want" ]; then
  gh variable set CI_RUNS_ON -R "$REPO" --body "$want" >/dev/null && echo "$(date -u +%FT%TZ) CI_RUNS_ON -> $want (laptop idle=$laptop_idle online=$laptop_online, pi idle=$pi_idle)"
fi
