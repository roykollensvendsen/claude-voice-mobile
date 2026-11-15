# The HTTP server's tests, seen red

For the server around the request reader, ADR-CVM-005.

- `red-server-no-read-timeout.txt`: `HttpServerTest` with the read timeout set at accept
  switched off. `a silent client is closed at the head limit` failed: with no timeout, a
  read on a silent socket blocks forever and the reader never sees its deadline.
- `red-server-no-write-watchdog.txt`: `HttpServerTest`, three runs with the watchdog's
  close removed. `an answer the client will not read is cut off at the write limit`
  failed each time.

The write-limit test first passed without the watchdog now and then. It checked for "no
connection open", which is also true before the server has accepted the connection. It now
waits to see the connection open first. Pitest found this: the mutant that removes the
watchdog survived.

`a lower-case post never reaches the gate` passed from the start. The routing already
matched `POST` exactly, and the test holds that.

`a bind that fails is reported` was red for its new words, "close the app using port
8812", before the message gained them.

## After review of #64

- `red-server-stop-race-and-answers.txt`: five new tests, all red before their fixes.
  - A connection accepted just as the server stops was served on. It is now refused under the
    lock `stop()` takes its snapshot with.
  - Stopping while an answer begins let `RejectedExecutionException` from the watchdog escape
    the connection thread. It is now caught, and the connection closes.
  - A handler that throws left the hub with no answer. It now gets a 500 with an error body
    that holds no detail.
  - A handler's `content-length` in another case went out beside the server's own. Any header
    naming a framing header, in any case, is now dropped.
  - A CR or LF in a handler's header could split the answer. That answer is now a 500.
- `red-server-accept-failure.txt`: one failed accept, such as too many open files, ended the
  accept thread while the socket stayed open. The server now keeps accepting.
- `red-server-accept-busy-loop.txt`: with the pause after a failed accept removed, an accept
  that keeps failing spun in a busy loop, and the test that counts tries failed.
