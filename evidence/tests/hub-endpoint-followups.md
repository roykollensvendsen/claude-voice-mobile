# Follow-ups from review of #73

- `red-endpoint-followups.txt`: two tests, each red with its fix switched off.
  - `with no code open, a pairing try is refused before any name lookup`: with the lookup run
    whether or not a code was open, the test counted one lookup.
  - `before pairing, an address off the tailnet is not let in`: with `admits()` not checking the
    tailnet range, 192.168.1.20 got in before pairing.
- `a name lookup that hangs is cut off, and answered as no name` did not compile at first,
  since `HubEndpoint` had no `lookupLimit`. The lookup it uses waits until the test releases
  it, and the answer comes after 100 ms.
- `red-evidence-local-path.txt`: `tests/test_evidence_paths.py` found the full local path in
  `red-endpoint-failure-kind.txt`, two lines. That file now names files from the repository
  root.
- Pitest first kept a mutant in the code check's expiry boundary. With `isOpen()` asked first,
  the boundary inside the lock was only reachable in a race. Both now use one private `open()`,
  so the boundary has one place and its tests reach it.
