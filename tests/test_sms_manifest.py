"""The text-message plugin's own manifest: what it may hold, and who may hand it a message.

ADR-CVM-010 says the reading plugin declares ``RECEIVE_SMS`` and ``READ_SMS``,
never ``SEND_SMS``, and hears new messages through a receiver for Android's
``SMS_RECEIVED`` broadcast. That receiver must be exported, since the system
sends the broadcast, but only the system may send it: without
``android.permission.BROADCAST_SMS`` any app on the phone could hand it a made-up
message, and the hub would read it as one of the owner's.
"""

import pathlib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).parent.parent
MANIFEST = ROOT / "plugins/sms/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED"


def _manifest() -> ET.Element:
    return ET.parse(MANIFEST).getroot()  # noqa: S314 - our own manifest


def _permissions(root: ET.Element) -> set[str]:
    return {e.get(f"{ANDROID}name", "") for e in root.iter("uses-permission")}


def _receivers_of(root: ET.Element, action: str) -> list[ET.Element]:
    return [
        r for r in root.iter("receiver") if any(a.get(f"{ANDROID}name") == action for a in r.iter("action"))
    ]


def test_the_reading_plugin_holds_what_it_reads_with_and_never_the_right_to_send() -> None:
    held = _permissions(_manifest())
    assert {"android.permission.RECEIVE_SMS", "android.permission.READ_SMS"} <= held
    assert "android.permission.SEND_SMS" not in held


def test_one_receiver_hears_new_messages_and_only_the_system_may_send_to_it() -> None:
    receivers = _receivers_of(_manifest(), SMS_RECEIVED)
    assert len(receivers) == 1
    receiver = receivers[0]
    assert receiver.get(f"{ANDROID}exported") == "true"
    assert receiver.get(f"{ANDROID}permission") == "android.permission.BROADCAST_SMS"


def test_the_check_finds_a_receiver_anyone_could_send_to() -> None:
    # Keeps the test above honest: the same check on a receiver without the guard fails.
    open_receiver = ET.fromstring(  # noqa: S314 - written right here
        f'<manifest xmlns:android="{ANDROID[1:-1]}"><application>'
        '<receiver android:name=".R" android:exported="true"><intent-filter>'
        f'<action android:name="{SMS_RECEIVED}" /></intent-filter></receiver>'
        "</application></manifest>"
    )
    receiver = _receivers_of(open_receiver, SMS_RECEIVED)[0]
    assert receiver.get(f"{ANDROID}permission") != "android.permission.BROADCAST_SMS"
