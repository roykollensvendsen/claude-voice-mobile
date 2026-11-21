# Texting a contact by name

ADR-CVM-010, "The next version", the part "By name", with one recipient. Several recipients,
searching older texts and delivery reports are later pull requests.

Every test below was seen red first; the runs are in `red-sms-by-name.txt`, one part each.

**The rules** (`plugins/sms_send/rules`, mutated by Pitest):
- `Names`: a query matches a whole first name, a whole last name or the full name, ignoring
  case and accents (é and å fold to e and a; ø and æ stay letters of their own). A prefix, a part
  of a name, a middle name or a part of a double name never matches. A query under 2 letters is
  refused. One mobile number for the name is the recipient; anything else that matched, a home
  or work number alone included, is a choice of at most 5; nothing is `not_found`. Rows with one
  number are one row before anything is decided or capped, a mobile one kept over another, and one
  the contacts provider wrote kept over a guessed one; so a contact saved twice is never two
  choices and repeats never crowd out a real number. A number not in full international form is
  left out, since it could not be sent to. Past 5, the choices end with "And more match; say the
  full name." A shown name is cleaned and cut to 40 characters, as a sender's name is.
- `Recipients`: a number given as a number is no lookup. At most 10 lookups an hour, in memory,
  so a restart starts again; past that the refusal is `rate_limited`, "too many name lookups; say
  the number instead". A lookup the phone may not make is `permission_missing`, with where to
  allow it, and is not counted. A choices answer holds, for each, an opaque `pick` and words such
  as "Kari Nordmann, mobile ending 12", never the whole number. A pick works only from the latest
  choices, and once: a new answer or any draft closes the choices before it, and an older pick is
  refused as `unknown_pick`. A request whose text is refused closes them too.
- The read-back of a draft by name names the contact and the whole number. The draft stores the
  number, so a contact changed later does not move the text. The answer to the owner's yes
  names the contact as well. The text rules are checked before
  any lookup, so a refused text costs none, and the send limits hold as before.

**The capability** (`capabilities/contacts`) is the only module that declares `READ_CONTACTS`.
It answers which numbers match a name, every one, so the caller's rule decides what is shown. What counts as a match is
handed in by the rules, so the rule is tested and mutated where it lives. How it turns a row into
an entry is tested on its own: a number the contacts provider wrote in full international form is
used as given, with nothing guessed (the test can show only that the provider gave it; the
provider wrote it from the country when the contact was saved); a number saved with its "+" is
used as written; a number saved without a code is read in the phone's country, and the code put
in front is marked as guessed, "+47" for a Norwegian SIM, "+44" after a trunk zero. The reading
of Android's contacts and the real `PhoneNumberUtils` conversion are not unit tested, since the
unit tests have no contacts provider and Android's library there is only stubs; they are proven
only on a device or the emulator.

**The country** a number saved without a code is read in is the SIM's, else the phone's region
setting, never the network's: while roaming that would put a foreign code on a home contact
(`Countries`, tested: SIM `NO` with region `SE` gives `NO`; a blank SIM falls back to the region;
neither gives none). A guessed number is never a draft at once. It is offered only as a choice,
"Kari Nordmann, mobile ending 34, guessed +47", and picking it gives a read-back with the whole
number, code and all, before the owner's yes.

**The build** includes a capability only when a picked plugin's build file depends on it, so a
build without `sms_send` holds no `READ_CONTACTS`. The code-health measure walks `capabilities/`
too, and tests it as an Android library, not mutated.

**The merged manifest.** `MergedPermissionsTest` reads the manifest merger's own record of which
module each permission came from, for the build under test. With `sms_send` built, the sending
plugin brings `SEND_SMS` alone, and `READ_CONTACTS` comes only from the contacts capability.
Planting `READ_CONTACTS` in the sending plugin's manifest turns both tests red, which is the last
part of the red runs. CI runs it on `-PcvPlugins=all`.
