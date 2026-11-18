"""The laptop's side of the mic-mute measurements (ADR-CVM-008): the words, their times, and M1c's verdict."""

import importlib.util
import pathlib
import sys

import pytest

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


LOG = """\
2026-11-18T17:00:00Z +1.000s M1c m1c-schedule pattern=-M- starts=2026-11-18T17:01:00Z slot-ms=10000
2026-11-18T17:01:10.000Z +70.000s M1c mute source=schedule now=true
2026-11-18T17:01:20.000Z +80.000s M1c unmute source=schedule now=false
2026-11-18T17:01:40.000Z +100.000s M1c b-on app-muted=false
2026-11-18T17:01:50.000Z +110.000s M1c b-off app-muted=false
"""
T0 = 1_795_021_260.0  # 2026-11-18T17:01:00Z


def test_the_phone_log_gives_when_each_mute_began_and_ended_by_either_way():
    events = laptop.phone_events(LOG)
    assert laptop.muted_at(events, T0 + 5) is False
    assert laptop.muted_at(events, T0 + 15) is True
    assert laptop.muted_at(events, T0 + 25) is False
    assert laptop.muted_at(events, T0 + 45) is True


def test_m1c_passes_when_only_the_unmuted_words_reach_the_transcript():
    words = [(T0 + 5, "kalo"), (T0 + 15, "mevi"), (T0 + 25, "tunu")]
    verdict = laptop.judge(words, laptop.phone_events(LOG), "I heard Kalo and then tunu.")
    assert verdict["verdict"] == "pass"
    assert verdict["leaked"] == []
    assert verdict["missed"] == []


def test_m1c_fails_on_a_muted_word_in_the_transcript():
    words = [(T0 + 5, "kalo"), (T0 + 15, "mevi"), (T0 + 25, "tunu")]
    verdict = laptop.judge(words, laptop.phone_events(LOG), "kalo mevi tunu")
    assert verdict["verdict"] == "fail"
    assert verdict["leaked"] == ["mevi"]


def test_m1c_fails_when_an_unmuted_word_is_missing_since_then_the_test_could_not_fail():
    words = [(T0 + 5, "kalo"), (T0 + 25, "tunu")]
    verdict = laptop.judge(words, laptop.phone_events(LOG), "kalo")
    assert verdict["verdict"] == "fail"
    assert verdict["missed"] == ["tunu"]


def test_a_word_said_under_the_owners_switch_counts_as_muted():
    words = [(T0 + 45, "rado")]
    verdict = laptop.judge(words, laptop.phone_events(LOG), "rado")
    assert verdict["leaked"] == ["rado"]


def test_a_word_said_across_a_mute_or_an_unmute_is_judged_neither_way():
    words = [(T0 + 9.5, "kalo")]
    verdict = laptop.judge(words, laptop.phone_events(LOG), "")
    assert verdict["verdict"] == "unclear"
    assert verdict["straddled"] == ["kalo"]


def test_every_other_word_in_the_transcript_is_listed_for_the_owner_to_read():
    words = [(T0 + 15, "mevi")]
    verdict = laptop.judge(words, laptop.phone_events(LOG), "Did you say me vi? Kalo.")
    assert verdict["others"] == ["did", "kalo", "me", "say", "vi", "you"]


def test_a_word_inside_a_longer_word_is_not_counted_as_heard():
    verdict = laptop.judge([(T0 + 15, "mevi")], laptop.phone_events(LOG), "mevirad")
    assert verdict["leaked"] == []


def test_the_judge_refuses_when_the_phone_and_the_laptop_started_in_different_minutes():
    laptop.check_start(LOG, T0)
    with pytest.raises(ValueError, match="17:01:00"):
        laptop.check_start(LOG, T0 + 60)
