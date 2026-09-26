#!/usr/bin/env bash
#
# Multi-node routing through a Java TCPServerInterface acting as a transport hub.
#
#   peer B ---\
#              hub (enable_transport) --- peer A
#   peer C ---/
#
# Two cases, both of which only exist on a transport node and so were reached by
# nothing in the six-step plan — every earlier step was two nodes on one hop.
#
#   1  routing: a link across two hops, carrying a frame at the negotiated MDU.
#      This is the topology once recorded in CLAUDE.md as broken ("links go
#      PENDING when clients connect through a TCP server"). It is also the first
#      live exercise of the relay-side link-request MTU clamp, which only runs on
#      a transport node.
#
#   2  path request batching: peers B and C ask for the same destination while it
#      is still unknown to the whole network. The hub must issue one recursive
#      search, record both requesters, and answer both when the announce finally
#      arrives. With only the first requester recorded, C has to wait out its own
#      retry instead.
#
# Usage:  ./run.sh
#
# Everything runs on 127.0.0.1:42508. No external network is touched.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/.work"
EXPECTED_MTU="${EXPECTED_MTU:-16384}"

# Peer A is not started at all until B and C have asked and been batched, so a
# fixed identity is used to know its destination hash up front.
# 64 bytes: a 32-byte X25519 private key followed by a 32-byte Ed25519 one.
A_IDENTITY="${A_IDENTITY:-0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90}"
BATCH_WINDOW="${BATCH_WINDOW:-12}"
# Comfortably under Java's path-request retry interval, so a pass cannot be a
# second attempt succeeding.
PATH_TIMEOUT_MS="${PATH_TIMEOUT_MS:-60000}"

PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [[ -n "$p" ]] && kill "$p" 2>/dev/null; done; }
trap cleanup EXIT

say() { printf '\n=== %s ===\n' "$1"; }

start_hub() {
    rm -rf "$HERE/hub_config/storage"
    timeout 200 java -cp "$CP" examples.Step6IngressVictim "$HERE/hub_config" 180 > "$1" 2>&1 &
    HUB_PID=$!
    PIDS+=("$HUB_PID")
    for _ in $(seq 1 60); do
        grep -aq '\[step6-victim\] ready' "$1" 2>/dev/null && return 0
        sleep 0.5
    done
    echo "FAIL: the hub did not start"
    grep -avE "DEBUG|TRACE" "$1" | tail -15 | sed 's/^/  | /'
    return 1
}

stop_hub() { kill "$HUB_PID" 2>/dev/null; wait "$HUB_PID" 2>/dev/null; sleep 2; }

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

# ===========================================================================
say "Case 1: a link and a full-MDU frame across two hops"

start_hub "$WORK/hub1.log" || exit 1
echo "hub up on 127.0.0.1:42508 with transport enabled"

timeout 180 java -cp "$CP" examples.Step5FramingServer "$HERE/peer_a_config" > "$WORK/peer_a.log" 2>&1 &
PIDS+=($!)
A_PID=$!
DEST=""
for _ in $(seq 1 60); do
    DEST="$(grep -a 'destination <' "$WORK/peer_a.log" 2>/dev/null | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
    [[ -n "$DEST" ]] && break
    sleep 0.5
done
if [[ -z "$DEST" ]]; then
    echo "FAIL: peer A did not report a destination"
    grep -avE "DEBUG|TRACE" "$WORK/peer_a.log" | tail -15 | sed 's/^/  | /'; exit 1
fi
echo "peer A destination: $DEST"
sleep 6

timeout 180 java -cp "$CP" examples.Step5FramingClient "$HERE/peer_b_config" "$DEST" "$EXPECTED_MTU" \
    > "$WORK/peer_b.log" 2>&1
RC1=$?
grep -aE "^\[step5\]" "$WORK/peer_b.log" | sed 's/^/  /'
grep -aE "^\[step5-server\] link|^\[step5-server\] received" "$WORK/peer_a.log" | sed 's/^/  /'
[[ $RC1 -eq 0 ]] || { echo "  -> FAILED"; FAILURES=$((FAILURES+1)); }

kill "$A_PID" 2>/dev/null; stop_hub

# ===========================================================================
say "Case 2: two peers batched onto one path request"

# Learn the hash without starting the node: a node answers a path request for a
# destination it hosts whatever its announce schedule says, so peer A must be
# genuinely absent while B and C ask, not merely quiet.
rm -rf "$HERE/peer_a_config/storage"
timeout 60 java -cp "$CP" examples.Step7DelayedDestination "$HERE/peer_a_config" -1 "$A_IDENTITY" \
    > "$WORK/peer_a_hash.log" 2>&1
DEST2="$(grep -a 'destination <' "$WORK/peer_a_hash.log" | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
if [[ -z "$DEST2" ]]; then
    echo "FAIL: could not derive the destination hash"
    grep -avE "DEBUG|TRACE" "$WORK/peer_a_hash.log" | tail -15 | sed 's/^/  | /'; exit 1
fi
rm -rf "$HERE/peer_a_config/storage"

start_hub "$WORK/hub2.log" || exit 1
echo "destination $DEST2 — nothing on the network hosts it yet"

echo "peers B and C requesting it at the same time"
timeout 120 java -cp "$CP" examples.Step7PathRequester "$HERE/peer_b_config" "$DEST2" "$PATH_TIMEOUT_MS" B \
    > "$WORK/peer_b2.log" 2>&1 &
B_PID=$!; PIDS+=("$B_PID")
timeout 120 java -cp "$CP" examples.Step7PathRequester "$HERE/peer_c_config" "$DEST2" "$PATH_TIMEOUT_MS" C \
    > "$WORK/peer_c2.log" 2>&1 &
C_PID=$!; PIDS+=("$C_PID")

# Let both requests land and batch before the destination appears
sleep "$BATCH_WINDOW"
echo "peer A joining and announcing"
timeout 200 java -cp "$CP" examples.Step7DelayedDestination "$HERE/peer_a_config" 0 "$A_IDENTITY" \
    > "$WORK/peer_a2.log" 2>&1 &
PIDS+=($!)

wait "$B_PID"; RC_B=$?
wait "$C_PID"; RC_C=$?

grep -aE "^\[step7-B\]" "$WORK/peer_b2.log" | sed 's/^/  /'
grep -aE "^\[step7-C\]" "$WORK/peer_c2.log" | sed 's/^/  /'

echo "  hub:"
grep -a "Batching path request\|answering waiting discovery path request" "$WORK/hub2.log" \
    | sed -E 's/^.*- /    /'

BATCHED=$(grep -ac "Batching path request" "$WORK/hub2.log")
ANSWERED=$(grep -ac "answering waiting discovery path request" "$WORK/hub2.log")

stop_hub

[[ $RC_B -eq 0 ]] || { echo "  FAIL: peer B never resolved the path"; FAILURES=$((FAILURES+1)); }
[[ $RC_C -eq 0 ]] || { echo "  FAIL: peer C never resolved the path"; FAILURES=$((FAILURES+1)); }
[[ "$BATCHED" -ge 1 ]] || { echo "  FAIL: the hub never batched a second requester (saw $BATCHED)"; FAILURES=$((FAILURES+1)); }
[[ "$ANSWERED" -ge 2 ]] || { echo "  FAIL: the hub answered $ANSWERED waiting requester(s), expected 2"; FAILURES=$((FAILURES+1)); }

# ===========================================================================
say "Result"
if [[ $FAILURES -eq 0 ]]; then
    echo "PASS — a link carries a full-MDU frame across two hops through a Java"
    echo "       TCPServerInterface hub, and two peers asking for the same unknown"
    echo "       destination are batched onto one search and both answered."
else
    echo "FAIL — $FAILURES check(s) failed. Logs in $WORK/"
fi
exit $FAILURES
