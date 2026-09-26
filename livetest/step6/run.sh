#!/usr/bin/env bash
#
# Step 6 of the live verification plan: announce ingress control.
#
# A flood of announces for previously unknown destinations should make the
# receiving interface engage its limiter, hold a bounded number of announces,
# and then drain them once the flood stops. Getting any one of the three wrong
# has a different failure mode: never engaging is a DoS hole, never holding
# loses announces outright, never draining loses them just as surely but more
# quietly.
#
# The same Python flooder drives both a Java victim and a reference RNS 1.5.2
# victim with identical ingress-control settings, so the two once-a-second
# series can be compared directly. The reference is the specification here.
#
# Timings are scaled down in both configs (burst hold and penalty 4s, release
# interval 1s, at most 20 held) so a full cycle completes in under a minute.
#
# Usage:  ./run.sh
#
# Everything runs on 127.0.0.1:42506-42507. No external network is touched.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
RNS_SRC="${RNS_SRC:-$HOME/git/Reticulum}"
WORK="$HERE/.work"

COUNT="${COUNT:-60}"       # announces in the flood
RATE="${RATE:-20}"         # announces per second — well above the threshold of 3
OBSERVE="${OBSERVE:-45}"   # seconds of reporting
SETTLE=6                   # seconds before the flood, for the interface to come up

PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [[ -n "$p" ]] && kill "$p" 2>/dev/null; done; }
trap cleanup EXIT

say() { printf '\n=== %s ===\n' "$1"; }

mkdir -p "$WORK"
rm -f "$WORK"/*.log
rm -rf "$HERE"/*_config/storage

say "Preparing"
[[ -d "$RNS_SRC/RNS" ]] || { echo "FAIL: no Reticulum checkout at $RNS_SRC"; exit 1; }
PY_VERSION="$(cd "$RNS_SRC" && PYTHONPATH="$RNS_SRC" python3 -c 'import RNS._version as v; print(v.__version__)')"
echo "Reference RNS: $PY_VERSION"
echo "Flood: $COUNT announces at $RATE/s (threshold is 3/s), observing ${OBSERVE}s"

CP_FILE="$WORK/classpath.txt"
[[ -s "$CP_FILE" ]] || (cd "$REPO" && mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE") \
    || { echo "FAIL: classpath"; exit 1; }
(cd "$REPO" && mvn -o -q test-compile) || { echo "FAIL: test-compile"; exit 1; }
CP="$(cat "$CP_FILE"):$REPO/target/classes:$REPO/target/test-classes"

# run_side <label> <victim-start-command...>  -- flooder config is $2
run_side() {
    local label="$1" flooder_cfg="$2"; shift 2
    local vlog="$WORK/${label}_victim.log" flog="$WORK/${label}_flood.log"

    "$@" > "$vlog" 2>&1 &
    local vpid=$!
    PIDS+=("$vpid")

    for _ in $(seq 1 60); do
        grep -aq '\[step6-victim\] ready' "$vlog" 2>/dev/null && break
        sleep 0.5
    done
    if ! grep -aq '\[step6-victim\] ready' "$vlog"; then
        echo "  FAIL: the $label victim did not start"
        grep -avE "DEBUG|TRACE" "$vlog" | tail -15 | sed 's/^/     | /'
        kill "$vpid" 2>/dev/null
        return 1
    fi

    PYTHONPATH="$RNS_SRC" timeout 180 python3 -u "$HERE/flooder.py" \
        "$HERE/$flooder_cfg" "$COUNT" "$RATE" "$SETTLE" "$OBSERVE" > "$flog" 2>&1 &
    local fpid=$!
    PIDS+=("$fpid")
    wait "$vpid" 2>/dev/null
    kill "$fpid" 2>/dev/null
    grep -aE "^\[step6-flood\]" "$flog" | sed 's/^/  /'

    return 0
}

# Prints "engaged held_peak known_at_quiet known_final" for a victim log
summarise() {
    local log="$1"
    awk '
      /\[step6-victim\] t=/ {
        for (i = 1; i <= NF; i++) {
          split($i, kv, "=")
          if (kv[1] == "known") known = kv[2]
          if (kv[1] == "held")  held  = kv[2]
          if (kv[1] == "burst") burst = kv[2]
        }
        if (burst == "true" || burst == "True") engaged = 1
        if (held > peak) peak = held
        if (burst == "true" || burst == "True") known_engaged = known
        final = known
      }
      END { printf "%d %d %d %d\n", engaged, peak, known_engaged, final }
    ' "$log"
}

say "Java victim"
run_side java flooder_java_config \
    timeout 180 java -cp "$CP" examples.Step6IngressVictim "$HERE/victim_java_config" "$OBSERVE"
grep -aE "^\[step6-victim\] t=" "$WORK/java_victim.log" | sed 's/^/  /'

say "Reference victim (Python RNS $PY_VERSION)"
run_side python flooder_python_config \
    env PYTHONPATH="$RNS_SRC" timeout 180 python3 -u "$HERE/py_victim.py" "$HERE/victim_python_config" "$OBSERVE"
grep -aE "^\[step6-victim\] t=" "$WORK/python_victim.log" | sed 's/^/  /'

say "Comparison"
read -r J_ENGAGED J_PEAK J_ATBURST J_FINAL <<< "$(summarise "$WORK/java_victim.log")"
read -r P_ENGAGED P_PEAK P_ATBURST P_FINAL <<< "$(summarise "$WORK/python_victim.log")"

printf '%-28s %10s %10s\n' "" "Java" "reference"
printf '%-28s %10s %10s\n' "limiter engaged"        "$J_ENGAGED" "$P_ENGAGED"
printf '%-28s %10s %10s\n' "peak held announces"    "$J_PEAK"    "$P_PEAK"
printf '%-28s %10s %10s\n' "known when engaged"     "$J_ATBURST" "$P_ATBURST"
printf '%-28s %10s %10s\n' "known at end"           "$J_FINAL"   "$P_FINAL"

FAILURES=0
fail() { echo "  FAIL: $1"; FAILURES=$((FAILURES+1)); }

say "Checks"
[[ "$P_ENGAGED" == "1" ]] || fail "the reference never engaged its limiter — the flood was too weak to test anything"
[[ "$J_ENGAGED" == "1" ]] || fail "Java never engaged its limiter under a flood the reference limited"
[[ "$J_PEAK" -gt 0 ]]     || fail "Java held no announces while limiting (they were dropped, not held)"
[[ "$J_PEAK" -le 20 ]]    || fail "Java held $J_PEAK announces, above the configured ic_max_held_announces of 20"
[[ "$P_PEAK" -le 20 ]]    || fail "the reference held $P_PEAK announces, above its configured cap of 20"
[[ "$J_FINAL" -gt "$J_ATBURST" ]] || fail "Java never drained its held announces (known stayed at $J_ATBURST)"
[[ "$P_FINAL" -gt "$P_ATBURST" ]] || fail "the reference never drained its held announces"
[[ "$J_FINAL" -lt "$COUNT" ]] || fail "Java absorbed the entire flood ($J_FINAL of $COUNT) — nothing was limited"

# The two should end up in the same ballpark. A wide tolerance: this is a timing
# race between a 20-deep hold buffer and a 1/s drain, not an exact reproduction.
DELTA=$(( J_FINAL > P_FINAL ? J_FINAL - P_FINAL : P_FINAL - J_FINAL ))
TOLERANCE=$(( COUNT / 3 ))
[[ "$DELTA" -le "$TOLERANCE" ]] || fail "Java absorbed $J_FINAL announces against the reference's $P_FINAL (delta $DELTA > $TOLERANCE)"

say "Result"
if [[ $FAILURES -eq 0 ]]; then
    echo "PASS — ingress control engages, holds within its cap, and drains after the"
    echo "       flood, matching Python RNS $PY_VERSION under an identical load."
else
    echo "FAIL — $FAILURES check(s) failed. Logs in $WORK/"
fi
exit $FAILURES
