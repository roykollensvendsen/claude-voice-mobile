# The name lookup's own threads, and nameless tries counted

Follow-ups from review of #76.

- `red-lookup-pool.txt`, written before the code:
  - `the name lookup runs on a daemon thread of its own, and a stalled one is interrupted`
    failed on the shared common pool. That pool's threads are not named `name-lookup`, and
    CompletableFuture never interrupts a stalled lookup.
  - `a try from a machine with no name counts toward the five wrong tries` failed: the code
    stayed open after five nameless tries.
- Below them, `an interrupted connection ends its wait as no name, and keeps its interrupt
  flag` was added for the one line Pitest found untested. It was seen red with that line
  switched off.
- `red-evidence-other-systems.txt`: the pattern test, run against the old pattern, missed the
  macOS temporary folder. Writing that evidence, the guard then caught its own example path
  in the evidence file, so the file describes the path in words.
- CI's Pitest run found the interrupt test racy: one test failed without any mutation. When
  the lookup had already finished on its own thread, `Future.get()` returned the name without
  looking at the interrupt. The test's lookup now waits until the test releases it, and the
  test passed five runs in a row.
