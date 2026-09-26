#!/usr/bin/env python3
"""
Step 6 victim, reference side: the same once-a-second report as the Java victim,
so the two series can be compared directly.

Usage: py_victim.py <config-dir> <seconds>
"""

import sys
import time

import RNS


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    configdir, seconds = sys.argv[1], int(sys.argv[2])
    RNS.Reticulum(configdir)

    print("[step6-victim] ready", flush=True)

    for t in range(seconds):
        known = len(RNS.Transport.path_table)
        held = 0
        burst = False

        # A TCP server interface does the actual receiving on its spawned
        # children, which is where the limiter state lives. Those children are
        # registered in Transport.interfaces *and* listed on the parent, so
        # de-duplicate by identity or every count comes out doubled.
        seen = set()
        pending = list(RNS.Transport.interfaces)
        while pending:
            iface = pending.pop()
            if id(iface) in seen:
                continue
            seen.add(id(iface))
            held += len(getattr(iface, "held_announces", None) or {})
            burst = burst or getattr(iface, "ic_burst_active", False)
            pending.extend(getattr(iface, "spawned_interfaces", None) or [])

        print(f"[step6-victim] t={t} known={known} held={held} burst={burst}", flush=True)
        time.sleep(1)

    print("[step6-victim] done", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
