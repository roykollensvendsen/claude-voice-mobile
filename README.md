# claude-voice-mobile

The claude-voice agent for phones: phone functions as plugins for the hub.

[claude-voice](https://github.com/roykollensvendsen/claude-voice) lets a voice
assistant drive Claude Code and other tools on your own computers. One computer,
the hub, runs the bridge; every other machine runs a small agent that the hub
reaches over Tailscale. This app is that agent for an Android phone. It runs no
Claude Code sessions. It offers the phone's own functions as plugins, each
approved on its own: its status, notifications, location, phone calls,
playback, calls between machines, and later the camera.

The design is ADR-CV-007 in claude-voice, and the protocol is specified there.

**Nothing is built yet.** The first step is a
[prototype](docs/prototype-audio-focus.md) that answers the riskiest question:
can music duck, not pause, while Claude's voice runs?

Until the Android build lands, the code here is only the repository's own
checks, in Python: the commit-message rules and the tests that keep these pages
true. The Android gates join them with the prototype, as
[`decisions/deferred.md`](decisions/deferred.md) records.

## The gates

Every one of these runs in continuous integration, and all of them run here:

```console
$ uv run ruff check .
All checks passed!
```

<!-- not run: the workflow runs each of these; nesting them here would run the suite inside itself -->
```bash
uv run ruff format --check .   # layout
uv run mypy src                # types
uv run pytest -q               # behaviour
python scripts/mutate.py       # that each rule's own test can fail
```

`CONTRIBUTING.md` says what each one is for, and
[`decisions/`](decisions/README.md) records the choices that shaped them,
including how a change is made here.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).
