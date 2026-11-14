# Step 7d: a slow request must not hold up a later request on the same link.
# Sends /slow (the handler sleeps 8 s), then /git/list, and checks the list
# answer arrives first, while /slow is still outstanding.
import RNS, sys, time

cfg, dest_hex = sys.argv[1], sys.argv[2]
RNS.Reticulum(cfg)
dh = bytes.fromhex(dest_hex)
if not RNS.Transport.await_path(dh, timeout=15): print("[step7d] FAIL no path"); sys.exit(1)
d = RNS.Destination(RNS.Identity.recall(dh), RNS.Destination.OUT, RNS.Destination.SINGLE, "git", "repositories")
ready = []
link = RNS.Link(d, established_callback=lambda l: ready.append(1))
t = time.time()
while not ready and time.time() - t < 15: time.sleep(0.1)
link.identify(RNS.Identity())
time.sleep(0.5)

done = {}
start = time.time()
link.request("/slow", None, response_callback=lambda r: done.setdefault("slow", time.time() - start), timeout=30)
time.sleep(0.2)
link.request("/git/list", {0: "test/repo"}, response_callback=lambda r: done.setdefault("list", time.time() - start), timeout=30)
while len(done) < 2 and time.time() - start < 30: time.sleep(0.1)
link.teardown()

print(f"[step7d] list answered after {done.get('list', -1):.1f}s, slow after {done.get('slow', -1):.1f}s")
ok = "list" in done and "slow" in done and done["list"] < 3 and done["slow"] >= 7.5
print("[step7d] PASS" if ok else "[step7d] FAIL")
sys.exit(0 if ok else 1)
