# A call that waits too long for a thread is dropped (issue #85)

The red run is `red-queued-call.txt`.

- **The test, as quality set it.** Four calls hold the four call threads, blocking on a latch.
  A fifth, with the same 100 ms limit, answers "did not run". After the latch is released and
  the threads are free, the plugin has still been reached only four times. The fifth's id is
  free, and its retry runs.
- **A first fix was wrong, and an old test caught it.** It used `Future.cancel(false)` and took
  success to mean "never started". But a `FutureTask` stays in its new state while it runs, so
  cancelling succeeds on a running call too. The late-call test then got "did not run" about a
  call that was running. Now the thread that starts a call and the waiter that gives up both
  try to set one flag, and only the first wins.
- **The late-call test now warms the threads first.** On a fresh run, the first call took more
  than 100 ms just to load classes, so no thread had taken it yet. It now makes one quick call
  first, and has a 1 s limit.
- **An interrupted wait** may find the call taken or not, so its test accepts either honest
  answer. Each answer is checked on its own in the other tests.
- **A boolean id** (`"id": true`) is refused with `400`, as JSON-RPC allows only strings and
  numbers.

Pitest on `:core`: 451 made, 449 caught. The two left are main's, in `Tailnet.kt` and `KeptGate.kt`.
