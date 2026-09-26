#!/usr/bin/env bash
#
# Step 4 of the live verification plan: request/response, in both directions,
# against Python RNS 1.5.2 — including the metadata channel.
#
#   4a  Java requester  -> reference Examples/Request.py server
#   4b  Python requester -> Java request handlers (/small, /large, /file)
#
# 4b is the one that matters for the work added this round: only a file
# response carries metadata (RNS/Link.py:836-851), and that is the shape rngit
# uses for every fetch. Note that rngit itself cannot be pointed at a Java node
# — its client speaks to an "rngit.repositories" destination implementing
# eleven request handlers and its own protocol, which would mean porting
# rngit's server, not testing this library.
#
# Usage:  ./run.sh
#
# Everything runs on 127.0.0.1:42502. No external network is touched.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
RNS_SRC="${RNS_SRC:-$HOME/git/Reticulum}"

PY_CONFIG="$HERE/python_config"
JAVA_CONFIG="$HERE/java_config"
WORK="$HERE/.work"
LARGE_BYTES="${LARGE_BYTES:-120000}"

PIDS=()

cleanup() {
    for p in "${PIDS[@]:-}"; do
        [[ -n "$p" ]] && kill "$p" 2>/dev/null
    done
    exec 9>&- 2>/dev/null || true
    rm -f "$WORK/server_stdin"
}
trap cleanup EXIT

say() { printf '\n=== %s ===\n' "$1"; }

mkdir -p "$WORK"
rm -rf "$PY_CONFIG/storage" "$JAVA_CONFIG/storage" \
       "$HERE/java_server_config/storage" "$HERE/python_client_config/storage"
rm -f "$WORK"/*.log

say "Preparing"
[[ -d "$RNS_SRC/RNS" ]] || { echo "FAIL: no Reticulum checkout at $RNS_SRC"; exit 1; }
PY_VERSION="$(cd "$RNS_SRC" && PYTHONPATH="$RNS_SRC" python3 -c 'import RNS._version as v; print(v.__version__)')"
echo "Reference RNS: $PY_VERSION"

CP_FILE="$WORK/classpath.txt"
[[ -s "$CP_FILE" ]] || (cd "$REPO" && mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE") \
    || { echo "FAIL: classpath"; exit 1; }
(cd "$REPO" && mvn -o -q test-compile) || { echo "FAIL: test-compile"; exit 1; }
CP="$(cat "$CP_FILE"):$REPO/target/classes:$REPO/target/test-classes"

FAILURES=0

# ===========================================================================
say "4a: Java requester -> reference Examples/Request.py"

rm -f "$WORK/server_stdin"; mkfifo "$WORK/server_stdin"; exec 9<> "$WORK/server_stdin"

PYTHONPATH="$RNS_SRC" python3 -u "$RNS_SRC/Examples/Request.py" \
    -s --config "$PY_CONFIG" <&9 > "$WORK/py_server.log" 2>&1 &
PIDS+=($!)

DEST_A=""
for _ in $(seq 1 60); do
    DEST_A="$(grep -a 'running, waiting for a connection' "$WORK/py_server.log" 2>/dev/null \
        | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
    [[ -n "$DEST_A" ]] && break
    sleep 0.5
done
if [[ -z "$DEST_A" ]]; then
    echo "FAIL: could not read the reference server's destination"
    sed 's/^/  | /' "$WORK/py_server.log"; exit 1
fi
echo "Reference destination: $DEST_A"

# Examples/Request.py announces only on a stdin newline
( while true; do echo >&9; sleep 3; done ) & PIDS+=($!)
sleep 4

timeout 180 java -cp "$CP" examples.Step4RequestClient "$JAVA_CONFIG" "$DEST_A" \
    > "$WORK/java_client.log" 2>&1
RC_A=$?
grep -aE "^\[step4a\]" "$WORK/java_client.log" | sed 's/^/  /'
[[ $RC_A -eq 0 ]] || { echo "  -> 4a FAILED"; FAILURES=$((FAILURES+1)); }

# ===========================================================================
# 4b reverses the roles: the Java node listens (java_server_config, port 42503)
# and the Python requester connects in. Stop 4a's processes first.
for p in "${PIDS[@]:-}"; do [[ -n "$p" ]] && kill "$p" 2>/dev/null; done
PIDS=()
exec 9>&- 2>/dev/null || true
sleep 3

say "4b: Python requester -> Java request handlers"

timeout 180 java -cp "$CP" examples.Step4RequestServer "$HERE/java_server_config" "$LARGE_BYTES" \
    > "$WORK/java_server.log" 2>&1 &
PIDS+=($!)

DEST_B=""; FIRST32=""
for _ in $(seq 1 60); do
    DEST_B="$(grep -a 'destination <' "$WORK/java_server.log" 2>/dev/null \
        | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
    FIRST32="$(grep -aoP '(?<=\[step4b\] first32 ).*' "$WORK/java_server.log" 2>/dev/null | head -1)"
    [[ -n "$DEST_B" && -n "$FIRST32" ]] && break
    sleep 0.5
done
if [[ -z "$DEST_B" ]]; then
    echo "FAIL: the Java server did not report a destination"
    grep -avE "DEBUG|TRACE" "$WORK/java_server.log" | tail -20 | sed 's/^/  | /'; exit 1
fi
echo "Java destination: $DEST_B"
echo "Expecting large/file body of $LARGE_BYTES bytes, first32 $FIRST32"
sleep 5

PYTHONPATH="$RNS_SRC" timeout 240 python3 -u "$HERE/py_requester.py" \
    "$HERE/python_client_config" "$DEST_B" "$LARGE_BYTES" "$FIRST32" > "$WORK/py_requester.log" 2>&1
RC_B=$?
grep -aE "^\[step4b\]" "$WORK/py_requester.log" | sed 's/^/  /'
[[ $RC_B -eq 0 ]] || { echo "  -> 4b FAILED"; FAILURES=$((FAILURES+1)); }

# ===========================================================================
say "Result"
if [[ $FAILURES -eq 0 ]]; then
    echo "PASS — request/response verified in both directions against Python RNS $PY_VERSION,"
    echo "       including a file response carrying metadata."
else
    echo "FAIL — $FAILURES of 2 directions failed. Logs in $WORK/"
fi
exit $FAILURES
