# Each plugin says whether Android allows what it uses now

The red runs are in `red-access-states.txt`. They cover four things:

- the core's rule and the plugin entry, run against an `Access.of` whose body was left unwritten;
- the ask record, also run with its bodies left unwritten;
- `sms_send` before it named its permissions;
- the source check, run against main's notification ask, which asked without writing the record.

This builds ADR-CVM-011 on the phone.

What the tests pin:

- **The rule** (`AccessTest`, in the core, mutation-tested):
  - a use is "granted" only when every permission behind it is;
  - if one permission is not granted and the app asked for it before, the use is "refused", even when another permission was never asked for;
  - otherwise it is "never_asked";
  - a use with no Android permission behind it is "granted".
- **The entry.** Each plugin entry in `agent_info` ends with `access`. It has exactly one key for each name in `uses`, in the same order. It is read again on every answer. A refusal changes `access` and never `uses`. A plugin that names permissions for a use the protocol does not know is refused when the app starts.
- **The ask record** (`AskRecordTest`):
  - an install from before the record has asked for nothing;
  - an ask is written down once, by name only, in no-backup storage;
  - a name with a line break is refused;
  - a write that fails leaves the ask unrecorded and does not crash.
- **Which permissions each plugin names:**
  - `sms`: `messages` rests on `RECEIVE_SMS` and `READ_SMS`;
  - `sms_send`: `messages` rests on `SEND_SMS`, and `contacts` on `READ_CONTACTS`.
- **Nothing that reads can ask** (`tests/test_access_asks.py`). No file in `capabilities/permissions` names `requestPermissions` or a settings action. Every `requestPermissions` call in the app and its plugins writes the record in the lines just before it. Each check also has a test proving it catches the fault.

Special access, such as the data count behind `usage`, is not built yet, since no plugin uses it so far. The first plugin that does will add how its state is read.
