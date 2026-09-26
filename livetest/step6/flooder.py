#!/usr/bin/env python3
"""
Step 6 flooder: announces a burst of distinct, previously unknown destinations
at a fixed rate, then goes quiet.

Both victims — the Java node and the reference node — are flooded by this same
script, so any difference in what they absorb is a difference in the limiter,
not in the load.

Only announces for destinations the victim does not already know are subject to
ingress control (RNS/Transport.py:1804); re-announces of known destinations are
handled by ordinary announce rate limiting instead. Fresh identities every run
guarantee the former.

The flooder stays connected after the burst. Dropping the connection would tear
down the victim's spawned interface, taking its limiter state and its path-table
entries with it — the very thing being measured.

Usage: flooder.py <config-dir> <count> <per-second> <settle-seconds> <hold-seconds>
"""

import sys
import time

import RNS

APP_NAME = "step6flood"


def main():
    if len(sys.argv) != 6:
        print(__doc__)
        return 2

    configdir = sys.argv[1]
    count = int(sys.argv[2])
    rate = float(sys.argv[3])
    settle = float(sys.argv[4])
    hold = float(sys.argv[5])

    RNS.Reticulum(configdir)

    # Let the interface come up and the link to the victim establish
    time.sleep(settle)

    destinations = []
    for i in range(count):
        identity = RNS.Identity()
        destination = RNS.Destination(identity, RNS.Destination.IN, RNS.Destination.SINGLE,
                                      APP_NAME, f"target{i}")
        destinations.append(destination)

    print(f"[step6-flood] announcing {count} distinct destinations at {rate}/s", flush=True)
    interval = 1.0 / rate
    started = time.time()
    for i, destination in enumerate(destinations):
        destination.announce()
        time.sleep(interval)
    elapsed = time.time() - started
    print(f"[step6-flood] sent {count} announces in {elapsed:.1f}s "
          f"({count / elapsed:.1f}/s), going quiet", flush=True)

    # Stay connected while the victim drains, or tearing down the socket would
    # take the spawned interface — and the state under test — with it.
    time.sleep(hold)
    print("[step6-flood] disconnecting", flush=True)

    return 0


if __name__ == "__main__":
    sys.exit(main())
