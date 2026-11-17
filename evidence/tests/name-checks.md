# What a plugin uses, and the phone's name, are checked against protocol v1

The red run is `red-name-checks.txt`: the three new tests failed.

- **`uses` names only what v1 knows** (quality's n1 on #90): `microphone`, `camera`,
  `location`, `calls`, `contacts`, `notifications`, `media`, `sensors` and `usage`, once each.
  A typo such as `micropone`, a later version's name such as `a_later_thing`, a capital, an
  empty name and a repeat are each refused when the app starts. All nine together are accepted.
- **Checked again when the hub asks** (voice-architect's n1 on #90): `uses` is read live, so a
  plugin that starts reporting `micropone` while running makes `entries()` throw. The server
  answers a throwing handler with a `500` and no detail, so the hub never sees a wrong report.
- **The phone's name** (voice-architect's n2 on #90) follows v1's machine rule
  `^[a-z][a-z0-9-]{0,31}$`. A capital, a leading digit, an underscore, a space, an empty name
  and 33 characters are refused; 32 characters and `phone-2` are accepted.

Pitest on `:core` caught all but main's two old mutants, and the ratchet holds.
