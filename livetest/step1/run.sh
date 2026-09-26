#!/usr/bin/env bash
#
# Step 1 of the live verification plan: does the AES-256 Token port actually
# carry encrypted traffic to a Python RNS 1.5.2 peer?
#
# Starts the reference's own Examples/Link.py as a link server on loopback,
# then runs a Java client that establishes a link and exchanges encrypted data.
# A successful round trip proves the ciphers agree; link establishment alone
# does not, which is precisely why the AES-128/AES-256 mismatch was invisible.
#
# Usage:  ./run.sh
#
# Everything runs on 127.0.0.1:42500. No external network is touched.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
RNS_SRC="${RNS_SRC:-$HOME/git/Reticulum}"

PY_CONFIG="$HERE/python_config"
JAVA_CONFIG="$HERE/java_config"
WORK="$HERE/.work"
PY_LOG="$WORK/python_server.log"
JAVA_LOG="$WORK/java_client.log"

SERVER_PID=""
ANNOUNCER_PID=""

cleanup() {
    if [[ -n "$ANNOUNCER_PID" ]] && kill -0 "$ANNOUNCER_PID" 2>/dev/null; then
        kill "$ANNOUNCER_PID" 2>/dev/null
    fi
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
    fi
    exec 9>&- 2>/dev/null || true
    rm -f "$WORK/server_stdin"
}
trap cleanup EXIT

say() { printf '\n=== %s ===\n' "$1"; }

mkdir -p "$WORK"
rm -f "$PY_LOG" "$JAVA_LOG"
# Start from clean storage so the test never passes on a cached path or identity
rm -rf "$PY_CONFIG/storage" "$JAVA_CONFIG/storage"

# ---------------------------------------------------------------------------
say "Checking prerequisites"

if [[ ! -d "$RNS_SRC/RNS" ]]; then
    echo "FAIL: no Reticulum checkout at $RNS_SRC (override with RNS_SRC=/path)"
    exit 1
fi

PY_VERSION="$(cd "$RNS_SRC" && PYTHONPATH="$RNS_SRC" python3 -c \
    'import RNS._version as v; print(v.__version__)' 2>/dev/null)"
if [[ -z "$PY_VERSION" ]]; then
    echo "FAIL: could not determine the RNS version at $RNS_SRC"
    exit 1
fi
echo "Reference RNS: $PY_VERSION (from $RNS_SRC)"
if [[ "$PY_VERSION" != 1.5.* ]]; then
    echo "NOTE: expected a 1.5.x reference; continuing against $PY_VERSION"
fi

CP_FILE="$WORK/classpath.txt"
if [[ ! -s "$CP_FILE" ]]; then
    echo "Resolving Java classpath..."
    (cd "$REPO" && mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE") || {
        echo "FAIL: could not resolve the Java classpath"; exit 1; }
fi
(cd "$REPO" && mvn -o -q test-compile) || { echo "FAIL: Java test-compile failed"; exit 1; }
CP="$(cat "$CP_FILE"):$REPO/target/classes:$REPO/target/test-classes"
echo "Java classpath resolved"

# ---------------------------------------------------------------------------
say "Starting Python link server (reference Examples/Link.py -s)"

# Link.py's server_loop blocks on input(); an EOF terminates it. Hold a fifo
# open so stdin never closes and never delivers anything.
rm -f "$WORK/server_stdin"
mkfifo "$WORK/server_stdin"
exec 9<> "$WORK/server_stdin"

PYTHONPATH="$RNS_SRC" python3 -u "$RNS_SRC/Examples/Link.py" \
    -s --config "$PY_CONFIG" <&9 > "$PY_LOG" 2>&1 &
SERVER_PID=$!

# The server prints: "Link example ... running, waiting for a connection."
# preceded by the destination hash in the form <hexhash>
DEST_HASH=""
for _ in $(seq 1 60); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "FAIL: the Python server exited early. Log:"
        sed 's/^/  | /' "$PY_LOG"
        exit 1
    fi
    # Scrape only from the "running, waiting for a connection" line. At higher
    # log levels many unrelated hashes are printed before it, so a bare
    # first-match grep picks up the wrong one.
    DEST_HASH="$(grep -a 'running, waiting for a connection' "$PY_LOG" \
        | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
    [[ -n "$DEST_HASH" ]] && break
    sleep 0.5
done

if [[ -z "$DEST_HASH" ]]; then
    echo "FAIL: could not read the destination hash from the Python server. Log:"
    sed 's/^/  | /' "$PY_LOG"
    exit 1
fi
echo "Server destination: $DEST_HASH"

# Examples/Link.py only announces when a newline arrives on stdin. Drive it from
# the fifo so the Java side has an announce to learn the path and identity from,
# and keep announcing while the client is still looking.
announce_driver() {
    while true; do
        echo >&9
        sleep 3
    done
}
announce_driver &
ANNOUNCER_PID=$!

echo "Driving announces from the server; waiting for it to settle..."
sleep 4

# ---------------------------------------------------------------------------
say "Running Java client"

# No --add-opens required: the interface config model uses explicit-only
# Jackson property detection, so nothing inherited from Thread is reflected over.
java -cp "$CP" \
    examples.Step1InteropClient "$JAVA_CONFIG" "$DEST_HASH" 2>&1 | tee "$JAVA_LOG"
JAVA_RC="${PIPESTATUS[0]}"

# ---------------------------------------------------------------------------
say "Result"

if [[ "$JAVA_RC" -eq 0 ]]; then
    echo "PASS — AES-256 link encryption verified against Python RNS $PY_VERSION."
    echo
    echo "What this proves: a link was established to a reference peer, an"
    echo "encrypted packet was decrypted by it, and its encrypted reply was"
    echo "decrypted here. Both directions of link encryption agree."
else
    echo "FAIL — see details above."
    echo
    echo "Python server log (last 30 lines):"
    tail -30 "$PY_LOG" | sed 's/^/  | /'
    echo
    echo "Reading the failure:"
    echo "  - no path / no identity  -> announces are not crossing; interface problem,"
    echo "                              not a cipher problem"
    echo "  - link established, no reply -> the cipher mismatch signature. Look for a"
    echo "                              decryption error in the Python log above."
    echo "  - reply text mismatch    -> data crossed but was corrupted"
fi

echo
echo "Logs: $PY_LOG"
echo "      $JAVA_LOG"
exit "$JAVA_RC"
