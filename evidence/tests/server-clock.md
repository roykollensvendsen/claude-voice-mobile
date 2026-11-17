# The real server's clock is tested the same way on every run

The red run is `red-server-clock.txt`: the clock was not a value of its own yet.

In #105's first CI run, the mutation ratchet failed: 552 of 554 mutants killed on main, 551 on
the pull request. The failed run's Pitest report showed the one difference. It was
`PhoneAgent`'s real-server clock, `System::nanoTime`, replaced with `0L`, which #101 had accepted
as a survivor. On main's run a test killed it by chance of timing, and on the pull request's run
none did. A rerun went green, but a gate that a rerun can turn green is a weak one.

Now the clock is `PhoneAgent.MONOTONIC`, and the real server takes it as a parameter. One test
reads it, sleeps 5 ms, and reads it again: it must have moved at least 5 000 000 ns. A clock
stuck at `0L` fails that on every run.

Pitest on `:core` ran three times in a row, and then five more with the flaky test fixed. Each time its only survivors were main's two
old ones. The clock mutant was killed in all three. The ratchet holds.

## The real-server test was flaky too

This PR's first CI run failed before any mutant ran. Pitest needs a green suite, and the test
`the real server listens, turns away a caller off the tailnet, and closes when the agent stops`
failed without a mutation. #107's first run failed the same way. Later, on a loaded machine,
the plain test run failed it too, and the failure named the line: `Expected
java.net.ConnectException to be thrown, but nothing was thrown`.

After `stop()`, the test connected to the freed port to show it was closed. With many tests at
once, another test's server can already have been given that port, and the connect reached it.
The test now checks that the agent's listening socket is closed, which no other test can
change. It also counts a reset as "no answer", as well as an end of stream, since a close can
beat the read.
