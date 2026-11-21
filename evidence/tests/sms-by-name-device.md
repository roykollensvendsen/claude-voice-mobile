# Texting by name, run on an emulator

This records what #161 did on a device, an Android 15 emulator with made-up contacts. The
emulator has no tailnet, so a local helper called the plugin's tools through the core's own
`PluginCall`, the same path a call from the hub ends in. The helper was never committed. Only
the shape of each answer is written here. Every number in the test was made up: +47 00, or a
local US number from the 555-01xx range kept for fiction.

## The first head, e662808

| Contacts | Asked for | What came back |
|---|---|---|
| One person saved twice with one mobile number, once as +47… and once with spaces | the first name | two identical choices, "mobile ending 12" twice, and no draft |
| A mobile saved without a country code, and a work number with +47 | the first name | only the work number; the mobile was dropped without a word |
| A home number only | the full name | a choice, never a direct draft |
| none | "Nord", "Ka" | `not_found`: no part-of-name match |
| none | "Q" | refused: under two letters |
| none | a pick used twice, and a pick from an earlier request | `unknown_pick` both times |

The first two rows were faults. Both were fixed in ee985fa.

## The fixed head, ee985fa

| Contacts | Asked for | What came back |
|---|---|---|
| One person saved twice with one mobile number | the first name | a direct draft, read back with the name and the whole number |
| Six people with the same full name | the full name | five choices, then "And more match; say the full name." |
| A US fiction number saved without a country code, on the emulator's US SIM | the first name | a direct draft with +1, because the contacts store had already written the number in full (`NORMALIZED_NUMBER`) |
| A mobile no library can make valid, without a country code | the first name | still dropped; only the work number was offered |

The app did not crash in either run.

## Not seen on a device: tested at the seam

When the contacts store leaves a number in local form and the phone gives it the SIM's country,
the number is guessed. It should then be offered only as a choice, such as "guessed +47". That
path was not seen on a device, and is tested at the seam only. The emulator's contacts store
filled in every valid number by itself, and without root its SIM country stays "us". Android's
real conversion runs only on a device; the unit tests use a stand-in formatter.

The emulator also cannot say how often a real phone has numbers left in local form, which is how
often "text Kari" will ask first. The +47 00 numbers are not valid to the number library, so all
of them came back without `NORMALIZED_NUMBER`.

## First use on the owner's phone

Text a contact who is saved without +47. Expect a choice marked "guessed +47", and expect the
read-back to say the whole number, +47 included, before the yes.
