#!/usr/bin/env python3
"""
Step 4b requester: a Python RNS 1.5.2 client calling request handlers on a Java
node, verifying the responses and the metadata that comes with a file response.

This is the direction that matters for the metadata channel: only a file
response carries metadata (RNS/Link.py:836-851), and that is the shape rngit
relies on for every fetch.

Usage: py_requester.py <config-dir> <dest-hash-hex> <expected-large-bytes> <expected-first32-hex>
Exits 0 if every check passes, 1 otherwise.
"""

import os
import sys
import time
import threading

import RNS

APP_NAME = "example_utilities"

results = {}
done = threading.Event()
pending = set()


def log(stage, detail):
    print(f"[step4b] {stage}: {detail}", flush=True)


def record(name, ok, detail):
    results[name] = (ok, detail)
    pending.discard(name)
    if not pending:
        done.set()


def make_response_handler(name, verify):
    def got_response(receipt):
        try:
            verify(name, receipt)
        except Exception as e:
            record(name, False, f"verification raised: {e}")

    return got_response


def make_failed_handler(name):
    def failed(receipt):
        record(name, False, f"request failed, status={receipt.status}")

    return failed


def main():
    if len(sys.argv) != 5:
        print(__doc__)
        return 2

    configdir, dest_hex, expected_large, expected_first32 = sys.argv[1:5]
    expected_large = int(expected_large)
    dest_hash = bytes.fromhex(dest_hex)

    RNS.Reticulum(configdir)

    log("path", "requesting path to the Java node")
    if not RNS.Transport.await_path(dest_hash, timeout=30):
        log("path", "FAILED: no path")
        return 1

    server_identity = RNS.Identity.recall(dest_hash)
    if server_identity is None:
        log("identity", "FAILED: could not recall identity")
        return 1

    destination = RNS.Destination(server_identity, RNS.Destination.OUT,
                                  RNS.Destination.SINGLE, APP_NAME, "requestexample")

    link_up = threading.Event()
    link = RNS.Link(destination)
    link.set_link_established_callback(lambda l: link_up.set())

    if not link_up.wait(timeout=30):
        log("link", "FAILED: not established")
        return 1
    log("link", f"established, mdu={link.mdu}")

    # --- /small: a plain byte response in a single packet -------------------
    def verify_small(name, receipt):
        body = receipt.response
        text = body.decode("utf-8") if isinstance(body, (bytes, bytearray)) else str(body)
        if text == "java-small-response":
            record(name, True, f'"{text}"')
        else:
            record(name, False, f'unexpected body: {text!r}')

    # --- /large: past the MDU, so it arrives as a resource -------------------
    def verify_large(name, receipt):
        body = receipt.response
        if not isinstance(body, (bytes, bytearray)):
            record(name, False, f"expected bytes, got {type(body).__name__}")
            return
        if len(body) != expected_large:
            record(name, False, f"length {len(body)}, expected {expected_large}")
            return
        if body[:32].hex() != expected_first32:
            record(name, False, f"first 32 bytes differ: {body[:32].hex()}")
            return
        record(name, True, f"{len(body)} bytes, content verified")

    # --- /file: a file response carrying metadata ---------------------------
    def verify_file(name, receipt):
        body = receipt.response
        metadata = receipt.metadata

        # A file response arrives as an open file object
        if hasattr(body, "read"):
            data = body.read()
        elif isinstance(body, (bytes, bytearray)):
            data = bytes(body)
        else:
            record(name, False, f"unexpected response type {type(body).__name__}")
            return

        if len(data) != expected_large:
            record(name, False, f"length {len(data)}, expected {expected_large}")
            return
        if data[:32].hex() != expected_first32:
            record(name, False, f"first 32 bytes differ: {data[:32].hex()}")
            return
        if not metadata:
            record(name, False, f"no metadata on the file response (got {metadata!r})")
            return
        if metadata.get("name") != "step4-file" or metadata.get("size") != expected_large:
            record(name, False, f"metadata mismatch: {metadata!r}")
            return

        record(name, True, f"{len(data)} bytes + metadata {metadata!r}")

    checks = [
        ("/small", verify_small),
        ("/large", verify_large),
        ("/file", verify_file),
    ]

    for path, _ in checks:
        pending.add(path)

    for path, verify in checks:
        log("request", f"calling {path}")
        link.request(path, data=None,
                     response_callback=make_response_handler(path, verify),
                     failed_callback=make_failed_handler(path),
                     timeout=120)
        # Serialise the calls so a failure is unambiguous
        deadline = time.time() + 150
        while path in pending and time.time() < deadline:
            time.sleep(0.2)
        if path in pending:
            record(path, False, "timed out waiting for a response")

    log("summary", "---")
    failures = 0
    for path, _ in checks:
        ok, detail = results.get(path, (False, "no result"))
        print(f"[step4b] {'PASS' if ok else 'FAIL'} {path}: {detail}", flush=True)
        if not ok:
            failures += 1

    try:
        link.teardown()
    except Exception:
        pass
    time.sleep(0.5)

    print(f"[step4b] RESULT: {'PASS' if failures == 0 else 'FAIL'}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
