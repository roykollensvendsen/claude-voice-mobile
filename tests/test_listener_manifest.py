"""The app's manifest: how the listener runs, and what may start it again.

ADR-CVM-003 runs the listener as one foreground service of the special-use type
only, and starts it again after a restart or an update with a receiver for
``BOOT_COMPLETED`` and ``MY_PACKAGE_REPLACED``, which needs
``RECEIVE_BOOT_COMPLETED``. It waits for the boot after the first unlock and never
listens for ``LOCKED_BOOT_COMPLETED``, because the hub's key opens only after the
unlock (#152).
"""

import pathlib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).parent.parent
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
BOOT = "android.intent.action.BOOT_COMPLETED"
UPDATED = "android.intent.action.MY_PACKAGE_REPLACED"
LOCKED_BOOT = "android.intent.action.LOCKED_BOOT_COMPLETED"


def _manifest() -> ET.Element:
    return ET.parse(MANIFEST).getroot()  # noqa: S314 - our own manifest


def _actions(receiver: ET.Element) -> set[str]:
    return {a.get(f"{ANDROID}name", "") for a in receiver.iter("action")}


def _restart_problems(root: ET.Element) -> list[str]:
    """What is wrong with how the manifest starts the listener again; empty when nothing."""
    problems = []
    held = {e.get(f"{ANDROID}name", "") for e in root.iter("uses-permission")}
    if "android.permission.RECEIVE_BOOT_COMPLETED" not in held:
        problems.append("RECEIVE_BOOT_COMPLETED is not held")
    starters = [r for r in root.iter("receiver") if _actions(r) & {BOOT, UPDATED, LOCKED_BOOT}]
    if [_actions(r) for r in starters] != [{BOOT, UPDATED}]:
        problems.append(f"the restart receivers hear {[sorted(_actions(r)) for r in starters]}")
    types = [s.get(f"{ANDROID}foregroundServiceType") for s in root.iter("service")]
    if types != ["specialUse"]:
        problems.append(f"the services' types are {types}")
    return problems


def test_one_receiver_starts_the_listener_after_a_restart_or_an_update_and_never_before_unlock() -> None:
    assert _restart_problems(_manifest()) == []


def test_the_check_finds_a_locked_boot_a_missing_permission_and_another_type() -> None:
    # Keeps the test above honest: the same check on a manifest that gets each part wrong fails.
    wrong = ET.fromstring(  # noqa: S314 - written right here
        f'<manifest xmlns:android="{ANDROID[1:-1]}"><application>'
        '<service android:name=".S" android:foregroundServiceType="specialUse|dataSync" />'
        '<receiver android:name=".R" android:exported="false"><intent-filter>'
        f'<action android:name="{BOOT}" /><action android:name="{LOCKED_BOOT}" />'
        "</intent-filter></receiver></application></manifest>"
    )
    assert len(_restart_problems(wrong)) == 3
