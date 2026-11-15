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
