"""The laptop's side of the mic-mute measurements (ADR-CVM-008): the words, their times, and M1c's verdict."""

import importlib.util
import pathlib
import sys

ROOT = pathlib.Path(__file__).parent.parent
SPEC = importlib.util.spec_from_file_location("mic_mute_laptop", ROOT / "prototype/mic-mute/laptop.py")
assert SPEC is not None
assert SPEC.loader is not None
laptop = importlib.util.module_from_spec(SPEC)
sys.modules["mic_mute_laptop"] = laptop
SPEC.loader.exec_module(laptop)


def test_the_words_are_made_up_different_and_the_same_for_the_same_seed():
    words = laptop.made_up_words(12, seed=7)
    assert len(set(words)) == 12
    assert words == laptop.made_up_words(12, seed=7)
    assert words != laptop.made_up_words(12, seed=8)


def test_each_word_is_said_in_the_middle_of_its_slot():
    assert laptop.slot_times(start=600.0, slots=3) == [605.0, 615.0, 625.0]


def test_the_laptop_starts_at_the_next_whole_minute_as_the_phone_does():
    assert laptop.next_minute(1_763_460_000.001) == 1_763_460_060.0
    assert laptop.next_minute(1_763_460_000.0) == 1_763_460_060.0


def test_the_phone_log_gives_the_pattern_and_its_start():
    log = (
        "2026-11-18T17:31:41.745898Z +8.172s M1c m1c-schedule pattern=-MM---MM---- "
        "starts=2026-11-18T17:32:00Z slot-ms=10000\n"
    )
    assert laptop.read_schedule(log) == ("-MM---MM----", "2026-11-18T17:32:00Z")


def test_m1c_passes_when_only_the_unmuted_words_reach_the_transcript():
    verdict = laptop.judge("-M-", ["kalo", "mevi", "tunu"], "I heard Kalo and then tunu.")
    assert verdict == {"verdict": "pass", "leaked": [], "missed": []}


def test_m1c_fails_on_a_muted_word_in_the_transcript():
    verdict = laptop.judge("-M-", ["kalo", "mevi", "tunu"], "kalo mevi tunu")
    assert verdict == {"verdict": "fail", "leaked": ["mevi"], "missed": []}


def test_m1c_fails_when_an_unmuted_word_is_missing_since_then_the_test_could_not_fail():
    verdict = laptop.judge("-M-", ["kalo", "mevi", "tunu"], "kalo")
    assert verdict == {"verdict": "fail", "leaked": [], "missed": ["tunu"]}


def test_a_word_inside_a_longer_word_is_not_counted_as_heard():
    verdict = laptop.judge("M", ["kalo"], "kalorie")
    assert verdict["leaked"] == []
