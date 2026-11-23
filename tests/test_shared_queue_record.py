"""Where the shared cursor queue lives, and how send outcomes are kept.

Pins ADR-CVM-007's rule for a capability that holds only logic shared by more
than one plugin, and ADR-CVM-010's rule that outcomes and the crash marker are
plain files, not sealed.
"""

import pathlib

ROOT = pathlib.Path(__file__).parent.parent
MODULES = (ROOT / "decisions/ADR-CVM-007-modules-each-install-picks.md").read_text()
SMS = (ROOT / "decisions/ADR-CVM-010-reading-and-sending-text-messages.md").read_text()


def flat(text: str) -> str:
    """The text with its line breaks and runs of spaces made single spaces."""
    return " ".join(text.split())


def test_a_capability_may_hold_pure_logic_shared_by_plugins():
    text = flat(MODULES)
    assert "A capability may also hold pure logic that more than one plugin uses" in text
    assert "`capabilities/cursorqueue`" in text
    assert "never in `core`, which no plugin may depend on" in text


def test_a_plugins_rules_may_use_a_pure_capability_and_nothing_that_reaches_android():
    text = flat(MODULES)
    assert "A plugin's rules may depend on a capability that holds only pure logic" in text
    assert "never on one that reaches Android" in text
    assert "Picking the plugin brings that capability along" in text
    assert "Pure logic means no Android API, no permission, and no file, network or process access" in text
    assert "`tests/test_cursorqueue_pure.py` scans every such capability" in text
    assert "checked in review until that test is merged" in text
    assert "and no reaching any of these by name or by native code" in text
    assert "`java.lang.reflect`, `Class.forName`, `System.loadLibrary` or `System.load`" in text


def test_outcomes_and_the_marker_are_plain_files_kept_out_of_backups():
    text = flat(SMS)
    assert "The outcomes and the crash marker are plain files" in text
    assert "written atomically, in the no-backup folder, and not sealed" in text
    assert "a lost key would drop the marker" in text
    assert "only tokens, positions, times, outcomes and the fixed reasons" in text
    assert "they are sealed, and that needs the owner's yes" in text
