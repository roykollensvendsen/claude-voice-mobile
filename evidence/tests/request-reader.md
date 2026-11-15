# The request reader's tests, seen red

For #61, the request reader of ADR-CVM-005.

## Recorded here

`red-reader-doubled-and-lines.txt` is the run of `RequestReaderTest` on 2026-11-15,
with the two tests added after review and before the fix:

- `one header name repeated past 64 lines is 431`: the reader counted distinct
  names, so 1,000 lines of `X: 1` were read as a valid request.
- `a doubled header the gate reads is 400`: only `Host` and `Content-Length` were
  refused when doubled.

Both failed, and the other 37 passed. The fix (count lines; refuse a doubled header
the reader, the gate or MCP reads) turned them green.

`red-reader-any-doubled-and-accept-time.txt` is the run after ADR-CVM-005 gained its
review rows, with the parameter added but unused:

- `any header name sent twice, in any case, is 400`: only headers the gate reads
  were refused when doubled.
- `the head limit runs from when the connection was accepted`: the five seconds ran
  from the first byte read.

Both failed, and the other 39 passed. `bytes after the body are left unread` passed
from the start: the reader never read past the length.

## Seen red, not recorded as a file

- **The first 33 tests and the fuzz test** were written before `RequestReader.kt`
  existed. They did not compile, which is red but shows no single rule failing.
  Pitest then switched off each rule in turn: every mutant in the reader is caught.
- **The three read-timeout tests** (`a read that times out with no data waits on`,
  `a client that sends nothing is dropped`, `a connection reset is a drop`) failed in
  the session before `next()` caught the timeout, but that run was not saved.

A rerun of the current tests against the older code, in a throwaway worktree, was
refused by this session's permissions, so it is not here.
