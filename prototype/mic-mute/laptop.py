"""The laptop's side of the mic-mute measurements in ADR-CVM-008.

``tone`` plays a steady tone for M1a and M4. ``words`` waits for the next whole
minute, as the phone's word schedule does, then says one made-up word in the
middle of each 10-second slot, for M1c. ``judge`` reads the phone's log, the
words file and the Claude app's transcript, and gives M1c's verdict.

Everything it writes goes to ``~/.local/state/claude-voice/measurements/mic-mute/``,
outside the repository: results never go into git.
"""

import argparse
import math
import pathlib
import random
import re
import shutil
import struct
import subprocess
import sys
import time
import wave

STATE = pathlib.Path.home() / ".local/state/claude-voice/measurements/mic-mute"
CONSONANTS = "bdfgklmnprstvz"
VOWELS = "aeiou"
SLOTS = 12
SLOT_S = 10.0
RATE = 48_000


def made_up_words(count: int, seed: int) -> list[str]:
    """``count`` different words of three syllables, the same for the same seed."""
    rng = random.Random(seed)  # noqa: S311 - a word list, not a secret
    words: list[str] = []
    while len(words) < count:
        word = "".join(rng.choice(CONSONANTS) + rng.choice(VOWELS) for _ in range(3))
        if word not in words:
            words.append(word)
    return words


def slot_times(start: float, slots: int, slot_s: float = SLOT_S) -> list[float]:
    """When each word is said: the middle of its slot, as seconds since the epoch."""
    return [start + i * slot_s + slot_s / 2 for i in range(slots)]


def next_minute(now: float) -> float:
    """The start of the next whole minute, the time the phone's schedule starts at too."""
    return (math.floor(now / 60) + 1) * 60.0


def read_schedule(log: str) -> tuple[str, str]:
    """The last word schedule in the phone's log: its pattern, and when it started."""
    found = re.findall(r"m1c-schedule pattern=(\S+) starts=(\S+)", log)
    if not found:
        message = "the phone's log has no m1c-schedule line"
        raise ValueError(message)
    pattern, starts = found[-1]
    return pattern, starts


def judge(pattern: str, words: list[str], transcript: str) -> dict[str, object]:
    """M1c: no muted word in the transcript, and every unmuted one, so the test could have failed."""
    said = set(re.findall(r"[a-z]+", transcript.lower()))
    leaked = [w for w, mark in zip(words, pattern, strict=False) if mark == "M" and w in said]
    missed = [w for w, mark in zip(words, pattern, strict=False) if mark != "M" and w not in said]
    return {"verdict": "fail" if leaked or missed else "pass", "leaked": leaked, "missed": missed}


def _say(text: str) -> None:
    sys.stdout.write(text + "\n")
    sys.stdout.flush()


def _play_tone(seconds: float) -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    path = STATE / "tone.wav"
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        frames = int(RATE * seconds)
        out.writeframes(
            b"".join(
                struct.pack("<h", int(12_000 * math.sin(2 * math.pi * 1000 * i / RATE)))
                for i in range(frames)
            )
        )
    _say(f"tone starts {time.time():.3f}")
    subprocess.run([_tool("paplay"), str(path)], check=True)  # noqa: S603 - our own file
    _say(f"tone ends {time.time():.3f}")


def _speak_words(seed: int) -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    words = made_up_words(SLOTS, seed)
    start = next_minute(time.time())
    out = STATE / f"words-{int(start)}.txt"
    begins = time.strftime("%H:%M:%S", time.localtime(start))
    _say(f"start the phone's schedule now; the words begin at {begins}")
    with out.open("w") as log:
        log.write(f"start {start:.0f} seed {seed}\n")
        for i, at in enumerate(slot_times(start, SLOTS)):
            time.sleep(max(0.0, at - time.time()))
            subprocess.run([_tool("espeak-ng"), words[i]], check=True)  # noqa: S603 - a made-up word
            log.write(f"{i} {time.time():.3f} {words[i]}\n")
            log.flush()
    _say(f"done; the words and their times are in {out}")


def _judge(phone_log: pathlib.Path, words_file: pathlib.Path, transcript: pathlib.Path) -> None:
    pattern, starts = read_schedule(phone_log.read_text())
    lines = words_file.read_text().splitlines()
    words = [line.split()[2] for line in lines[1:]]
    _say(f"schedule {pattern} from {starts}; laptop {lines[0]}")
    _say(str(judge(pattern, words, transcript.read_text())))


def _tool(name: str) -> str:
    found = shutil.which(name)
    if found is None:
        message = f"{name} is not installed"
        raise SystemExit(message)
    return found


def main() -> None:
    """Parse the command line and run one step."""
    parser = argparse.ArgumentParser(description=__doc__)
    steps = parser.add_subparsers(dest="step", required=True)
    tone = steps.add_parser("tone", help="play a steady 1 kHz tone, for M1a and M4")
    tone.add_argument("--seconds", type=float, default=120.0)
    words = steps.add_parser("words", help="say the made-up words from the next whole minute, for M1c")
    words.add_argument("--seed", type=int, required=True)
    verdict = steps.add_parser(
        "judge", help="M1c's verdict from the phone's log, the words and the transcript"
    )
    verdict.add_argument("phone_log", type=pathlib.Path)
    verdict.add_argument("words_file", type=pathlib.Path)
    verdict.add_argument("transcript", type=pathlib.Path)
    args = parser.parse_args()
    if args.step == "tone":
        _play_tone(args.seconds)
    elif args.step == "words":
        _speak_words(args.seed)
    else:
        _judge(args.phone_log, args.words_file, args.transcript)


if __name__ == "__main__":
    main()
