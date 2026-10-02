#!/usr/bin/env bash
#
# Step 7: the stock git-remote-rns from Python RNS against a Java node.
#
# Steps 4b and earlier used the reference's example scripts. This one drives
# the real rngit client through git itself, against Step7GitServer, a minimal
# Java responder for /git/list, /git/fetch and /git/push:
#
#   7a  git clone rns://<java>/test/repo   map requests + multi-MB, multi-segment
#                                          file response with {1: 0} metadata
#   7b  git push                           large map request carrying a bundle
#   7c  git clone again                    the pushed commit is served back
#
# Usage:  ./run.sh            (BLOB_MB=3 by default: incompressible, so the
#                              bundle exceeds MAX_EFFICIENT_SIZE and segments)
#
# Everything runs on 127.0.0.1:42507. No external network is touched.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
RNS_SRC="${RNS_SRC:-$HOME/git/Reticulum}"
BLOB_MB="${BLOB_MB:-3}"

WORK="$HERE/.work"
PIDS=()

cleanup() {
    for p in "${PIDS[@]:-}"; do
        [[ -n "$p" ]] && kill "$p" 2>/dev/null
    done
}
trap cleanup EXIT

say() { printf '\n=== %s ===\n' "$1"; }
fail() { echo "  -> $1 FAILED"; FAILURES=$((FAILURES+1)); }

rm -rf "$WORK" "$HERE/java_server_config/storage" "$HERE/python_client_config/storage"
mkdir -p "$WORK/bin"

say "Preparing"
[[ -d "$RNS_SRC/RNS" ]] || { echo "FAIL: no Reticulum checkout at $RNS_SRC"; exit 1; }
PY_VERSION="$(cd "$RNS_SRC" && PYTHONPATH="$RNS_SRC" python3 -c 'import RNS._version as v; print(v.__version__)')"
echo "Reference RNS: $PY_VERSION"

CP_FILE="$HERE/../step4/.work/classpath.txt"
[[ -s "$CP_FILE" ]] || CP_FILE="$WORK/classpath.txt"
[[ -s "$CP_FILE" ]] || (cd "$REPO" && mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE") \
    || { echo "FAIL: classpath"; exit 1; }
(cd "$REPO" && mvn -o -q test-compile) || { echo "FAIL: test-compile"; exit 1; }
CP="$(cat "$CP_FILE"):$REPO/target/classes:$REPO/target/test-classes"

# The stock remote helper, exactly as RNS installs it, on git's PATH
cat > "$WORK/bin/git-remote-rns" <<EOF
#!/usr/bin/env bash
export PYTHONPATH="$RNS_SRC"
exec python3 -c 'import sys; from RNS.Utilities.rngit.client import main; sys.argv[0] = "git-remote-rns"; main()' "\$@"
EOF
chmod +x "$WORK/bin/git-remote-rns"
export PATH="$WORK/bin:$PATH"
export RNGIT_CONFIG="$WORK/rngit_client"
export RNS_CONFIG="$HERE/python_client_config"
export GIT_AUTHOR_NAME=step7 GIT_AUTHOR_EMAIL=step7@example.invalid
export GIT_COMMITTER_NAME=step7 GIT_COMMITTER_EMAIL=step7@example.invalid

# Source history: a few small commits and one incompressible blob
SRC="$WORK/src"
git init -q -b master "$SRC"
for i in 1 2 3; do
    echo "line $i" >> "$SRC/README.md"
    git -C "$SRC" add README.md && git -C "$SRC" commit -q -m "commit $i"
done
head -c "$((BLOB_MB * 1024 * 1024))" /dev/urandom > "$SRC/blob.bin"
git -C "$SRC" add blob.bin && git -C "$SRC" commit -q -m "add ${BLOB_MB} MiB blob"
git clone -q --bare "$SRC" "$WORK/server.git"
EXPECTED_HEAD="$(git -C "$SRC" rev-parse HEAD)"
EXPECTED_BLOB="$(sha256sum "$SRC/blob.bin" | cut -d' ' -f1)"
echo "Source HEAD $EXPECTED_HEAD, blob sha256 ${EXPECTED_BLOB:0:16}..."

FAILURES=0

timeout 900 java -cp "$CP" examples.Step7GitServer "$HERE/java_server_config" "$WORK/server.git" \
    > "$WORK/java_server.log" 2>&1 &
PIDS+=($!)

DEST=""
for _ in $(seq 1 60); do
    DEST="$(grep -a '\[step7\] destination <' "$WORK/java_server.log" 2>/dev/null \
        | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
    [[ -n "$DEST" ]] && break
    sleep 0.5
done
[[ -n "$DEST" ]] || { echo "FAIL: no destination from the Java server"; tail -20 "$WORK/java_server.log"; exit 1; }
echo "Java destination: $DEST"
sleep 5
URL="rns://$DEST/test/repo"

# ===========================================================================
say "7a: git clone $URL"
START=$(date +%s)
timeout 300 git clone "$URL" "$WORK/clone1" > "$WORK/clone1.log" 2>&1
RC=$?
echo "  exit $RC after $(( $(date +%s) - START ))s"
if [[ $RC -eq 0 && "$(git -C "$WORK/clone1" rev-parse HEAD)" == "$EXPECTED_HEAD" \
      && "$(sha256sum "$WORK/clone1/blob.bin" | cut -d' ' -f1)" == "$EXPECTED_BLOB" ]]; then
    echo "  HEAD and blob match the source"
else
    sed 's/^/  | /' "$WORK/clone1.log" | tail -15; fail "7a"
fi

# ===========================================================================
say "7b: git push"
if [[ -d "$WORK/clone1/.git" ]]; then
    head -c $((256 * 1024)) /dev/urandom > "$WORK/clone1/pushed.bin"
    git -C "$WORK/clone1" add pushed.bin && git -C "$WORK/clone1" commit -q -m "pushed over rns"
    PUSHED_HEAD="$(git -C "$WORK/clone1" rev-parse HEAD)"
    timeout 300 git -C "$WORK/clone1" push origin master > "$WORK/push.log" 2>&1
    RC=$?
    SERVER_HEAD="$(git -C "$WORK/server.git" rev-parse refs/heads/master)"
    echo "  exit $RC, server master now ${SERVER_HEAD:0:12}"
    if [[ $RC -eq 0 && "$SERVER_HEAD" == "$PUSHED_HEAD" ]]; then
        echo "  server ref updated to the pushed commit"
    else
        sed 's/^/  | /' "$WORK/push.log" | tail -15; fail "7b"
    fi
else
    fail "7b (no clone to push from)"
fi

# ===========================================================================
say "7c: git clone again"
timeout 300 git clone "$URL" "$WORK/clone2" > "$WORK/clone2.log" 2>&1
RC=$?
if [[ $RC -eq 0 && "$(git -C "$WORK/clone2" rev-parse HEAD)" == "${PUSHED_HEAD:-x}" ]] \
      && cmp -s "$WORK/clone1/pushed.bin" "$WORK/clone2/pushed.bin"; then
    echo "  second clone carries the pushed commit"
else
    sed 's/^/  | /' "$WORK/clone2.log" | tail -15; fail "7c"
fi

say "Java server"
grep -a '^\[step7\]' "$WORK/java_server.log" | sed 's/^/  /'

say "Result"
if [[ $FAILURES -eq 0 ]]; then
    echo "PASS — stock git-remote-rns (RNS $PY_VERSION) cloned, pushed and re-cloned against a Java node."
else
    echo "FAIL — $FAILURES of 3 checks failed. Logs in $WORK/"
fi
exit $FAILURES
