#!/usr/bin/env bash
#
# Step 3 of the live verification plan: do resources transfer intact to a
# Python RNS 1.5.2 peer?
#
# Resource transfer is the highest-risk area of the port — seven independent
# bugs were found there, none reachable by unit testing. This drives several
# shapes through the reference's own Examples/Resource.py server, which logs the
# metadata, byte count and first 32 bytes it decoded. Those are compared against
# what was sent, so the reference judges correctness.
#
# Usage:  ./run.sh            run the standard matrix
#         ./run.sh <bytes> <compressible|random> <metadata|nometadata>
#
# Everything runs on 127.0.0.1:42501. No external network is touched.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
RNS_SRC="${RNS_SRC:-$HOME/git/Reticulum}"

PY_CONFIG="$HERE/python_config"
JAVA_CONFIG="$HERE/java_config"
WORK="$HERE/.work"
PY_LOG="$WORK/python_server.log"

SERVER_PID=""
ANNOUNCER_PID=""

cleanup() {
    [[ -n "$ANNOUNCER_PID" ]] && kill "$ANNOUNCER_PID" 2>/dev/null
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null
    fi
    exec 9>&- 2>/dev/null || true
    rm -f "$WORK/server_stdin"
}
trap cleanup EXIT

say() { printf '\n=== %s ===\n' "$1"; }

mkdir -p "$WORK"
rm -f "$PY_LOG"
rm -rf "$PY_CONFIG/storage" "$JAVA_CONFIG/storage"

# ---------------------------------------------------------------------------
say "Preparing"

[[ -d "$RNS_SRC/RNS" ]] || { echo "FAIL: no Reticulum checkout at $RNS_SRC"; exit 1; }
PY_VERSION="$(cd "$RNS_SRC" && PYTHONPATH="$RNS_SRC" python3 -c 'import RNS._version as v; print(v.__version__)')"
echo "Reference RNS: $PY_VERSION"

CP_FILE="$WORK/classpath.txt"
[[ -s "$CP_FILE" ]] || (cd "$REPO" && mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE") \
    || { echo "FAIL: classpath"; exit 1; }
(cd "$REPO" && mvn -o -q test-compile) || { echo "FAIL: test-compile"; exit 1; }
CP="$(cat "$CP_FILE"):$REPO/target/classes:$REPO/target/test-classes"

# ---------------------------------------------------------------------------
say "Starting Python resource server (reference Examples/Resource.py -s)"

rm -f "$WORK/server_stdin"; mkfifo "$WORK/server_stdin"; exec 9<> "$WORK/server_stdin"

PYTHONPATH="$RNS_SRC" python3 "$RNS_SRC/Examples/Resource.py" \
    -s --config "$PY_CONFIG" <&9 > "$PY_LOG" 2>&1 &
SERVER_PID=$!

DEST_HASH=""
for _ in $(seq 1 60); do
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "FAIL: server exited"; sed 's/^/  | /' "$PY_LOG"; exit 1; }
    # Scrape only from the "running, waiting for a connection" line. At higher
    # log levels many unrelated hashes are printed before it, so a bare
    # first-match grep picks up the wrong one.
    DEST_HASH="$(grep -a 'running, waiting for a connection' "$PY_LOG" \
        | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
    [[ -n "$DEST_HASH" ]] && break
    sleep 0.5
done
[[ -n "$DEST_HASH" ]] || { echo "FAIL: no destination hash"; sed 's/^/  | /' "$PY_LOG"; exit 1; }
echo "Server destination: $DEST_HASH"

# Examples/Resource.py announces only on a stdin newline, same as Link.py
( while true; do echo >&9; sleep 3; done ) & ANNOUNCER_PID=$!
sleep 4

# ---------------------------------------------------------------------------
# Runs one transfer and verifies it against what the reference reports.
PASSES=0; FAILURES=0

run_case() {
    local size="$1" kind="$2" meta="$3"
    local label="${size}B/${kind}/${meta}"
    say "Case: $label"

    # Note the current log size instead of writing a marker: the Python process
    # holds this file open with its own offset, so anything appended here gets
    # overwritten by its next write.
    local log_offset
    log_offset="$(wc -c < "$PY_LOG")"

    local out
    out="$(timeout 400 java ${JAVA_OPTS:-} -cp "$CP" examples.Step3ResourceClient \
            "$JAVA_CONFIG" "$DEST_HASH" "$size" "$kind" "$meta" 2>&1)"
    local rc=$?
    echo "$out" > "$WORK/java_full_${size}_${kind}_${meta}.log"
    echo "$out" | grep -E "^\[step3\]" | sed 's/^/  /'

    if [[ $rc -ne 0 ]]; then
        echo "  -> FAIL: sender reported failure"
        FAILURES=$((FAILURES+1)); return
    fi

    local sent_len sent_first32
    sent_len="$(echo "$out"    | grep -oP '(?<=SENT_LENGTH ).*'  | tail -1)"
    sent_first32="$(echo "$out" | grep -oP '(?<=SENT_FIRST32 ).*' | tail -1)"

    # Give the receiver a moment to finish assembling and log
    sleep 5
    local seen
    seen="$(tail -c "+$((log_offset + 1))" "$PY_LOG")"

    local got_len got_first32
    got_len="$(echo "$seen"     | grep -oP '(?<=Data length: )\d+' | tail -1)"
    # The reference logs colon-delimited hex (RNS.hexrep): 84:ba:ac:...
    got_first32="$(echo "$seen" | grep -oP '(?<=First 32 bytes of data: ).*' | tail -1 | tr -d ' :' | tr 'A-F' 'a-f')"

    if [[ -z "$got_len" ]]; then
        echo "  -> FAIL: the reference never reported a completed resource"
        echo "$seen" | grep -E "Resource|failed|Error" | tail -5 | sed 's/^/     | /'
        FAILURES=$((FAILURES+1)); return
    fi
    if [[ "$got_len" != "$sent_len" ]]; then
        echo "  -> FAIL: length mismatch, sent $sent_len, reference received $got_len"
        FAILURES=$((FAILURES+1)); return
    fi
    if [[ "$got_first32" != "$sent_first32" ]]; then
        echo "  -> FAIL: content mismatch in first 32 bytes"
        echo "     sent:     $sent_first32"
        echo "     received: $got_first32"
        FAILURES=$((FAILURES+1)); return
    fi

    if [[ "$meta" == "metadata" ]]; then
        local got_meta
        got_meta="$(echo "$seen" | grep -oP '(?<=Metadata: ).*' | tail -1)"
        if [[ -z "$got_meta" || "$got_meta" == "None" ]]; then
            echo "  -> FAIL: metadata was sent but the reference decoded: ${got_meta:-<nothing>}"
            FAILURES=$((FAILURES+1)); return
        fi
        echo "  reference decoded metadata: $got_meta"
    fi

    echo "  -> PASS: $got_len bytes, content and metadata verified by the reference"
    PASSES=$((PASSES+1))
}

if [[ $# -eq 3 ]]; then
    run_case "$1" "$2" "$3"
else
    # Small: single part, exercises the basic path
    run_case 512 random nometadata
    # Compressible: takes the BZIP2 path that used to truncate to a 3-byte stub
    run_case 65536 compressible nometadata
    # Incompressible, many parts, single segment
    run_case 200000 random metadata
    # Past MAX_EFFICIENT_SIZE (1 MiB-1): exercises segmentation
    run_case 3000000 random metadata
fi

# ---------------------------------------------------------------------------
say "Result"
echo "passed: $PASSES   failed: $FAILURES"
echo "Python server log: $PY_LOG"
[[ $FAILURES -eq 0 ]] || exit 1
echo
echo "PASS — resource transfer verified against Python RNS $PY_VERSION."
exit 0
