#!/usr/bin/env bash
#
# Step 5 of the live verification plan: large-MTU framing.
#
# Two questions:
#   1. Is the link MTU negotiated down to what the receiving interface can
#      actually carry?
#   2. Does a frame at exactly that MTU cross intact?
#
# Both nodes are Java. That is the point: against the reference, the responder
# clamps the advertised MTU on our behalf (RNS/Transport.py:2544), so a missing
# clamp here stays invisible. Between two Java nodes nothing does — before this
# round Java confirmed whatever MTU it was offered.
#
#   5a  symmetric, both at the default 10 Mbps guess       -> expect 16384
#   5b  responder at 1 Mbps, initiator at 10 Mbps          -> expect 2048  (clamped)
#   5c  initiator at 1 Gbps, responder at 10 Mbps          -> expect 16384 (clamped from 524288)
#
# 5b and 5c are the ones that fail without the clamp: the responder would
# confirm the initiator's figure and the oversized frame check would then drop
# the traffic.
#
# Usage:  ./run.sh
#
# Everything runs on 127.0.0.1:42504-42505. No external network is touched.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/.work"

PIDS=()
cleanup() {
    for p in "${PIDS[@]:-}"; do [[ -n "$p" ]] && kill "$p" 2>/dev/null; done
}
trap cleanup EXIT

say() { printf '\n=== %s ===\n' "$1"; }

mkdir -p "$WORK"
rm -f "$WORK"/*.log
rm -rf "$HERE"/*_config/storage

say "Preparing"
CP_FILE="$WORK/classpath.txt"
[[ -s "$CP_FILE" ]] || (cd "$REPO" && mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE") \
    || { echo "FAIL: classpath"; exit 1; }
(cd "$REPO" && mvn -o -q test-compile) || { echo "FAIL: test-compile"; exit 1; }
CP="$(cat "$CP_FILE"):$REPO/target/classes:$REPO/target/test-classes"

FAILURES=0

# run_case <label> <server-config> <client-config> <expected-mtu>
run_case() {
    local label="$1" server_cfg="$2" client_cfg="$3" expected="$4"
    say "$label (expecting a negotiated MTU of $expected)"

    local slog="$WORK/${label}_server.log" clog="$WORK/${label}_client.log"

    timeout 180 java -cp "$CP" examples.Step5FramingServer "$HERE/$server_cfg" > "$slog" 2>&1 &
    local server_pid=$!
    PIDS+=("$server_pid")

    local dest=""
    for _ in $(seq 1 60); do
        dest="$(grep -a 'destination <' "$slog" 2>/dev/null | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
        [[ -n "$dest" ]] && break
        sleep 0.5
    done
    if [[ -z "$dest" ]]; then
        echo "  FAIL: the responder did not report a destination"
        grep -avE "DEBUG|TRACE" "$slog" | tail -15 | sed 's/^/     | /'
        FAILURES=$((FAILURES+1)); kill "$server_pid" 2>/dev/null; return
    fi
    echo "  responder destination: $dest"
    sleep 4

    timeout 180 java -cp "$CP" examples.Step5FramingClient "$HERE/$client_cfg" "$dest" "$expected" \
        > "$clog" 2>&1
    local rc=$?
    grep -aE "^\[step5\]" "$clog" | sed 's/^/  /'
    grep -aE "^\[step5-server\]" "$slog" | sed 's/^/  /'

    # What each side derived, for the record
    grep -a "hardware MTU set to" "$slog" | sed -E 's/^.*- /     responder iface: /' | sort -u
    grep -a "hardware MTU set to" "$clog" | sed -E 's/^.*- /     initiator iface: /' | sort -u
    grep -a "Clamped inbound link request MTU" "$slog" | sed -E 's/^.*- /     /' | head -1

    kill "$server_pid" 2>/dev/null
    wait "$server_pid" 2>/dev/null
    sleep 2

    [[ $rc -eq 0 ]] || { echo "  -> FAILED"; FAILURES=$((FAILURES+1)); }
}

run_case 5a server_default_config client_default_config   16384
run_case 5b server_narrow_config  client_to_narrow_config 2048
run_case 5c server_default_config client_wide_config      16384

say "Result"
if [[ $FAILURES -eq 0 ]]; then
    echo "PASS — the link MTU is clamped to what the receiving interface declares,"
    echo "       and a frame at exactly that MTU crosses intact."
else
    echo "FAIL — $FAILURES of 3 cases failed. Logs in $WORK/"
fi
exit $FAILURES
