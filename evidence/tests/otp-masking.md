# One-time codes are hidden before a text message is kept

The red run is in `red-otp-masking.txt`: 20 of the 27 tests failed against a masker that hid
nothing.

ADR-CVM-010 says one-time codes never leave the phone, and that the masking hides too much
rather than too little. `plugins/sms/rules`, the text-message plugin's first rules module
(ADR-CVM-007), now holds that masking, in plain Kotlin:

- From a short code (at most six digits, no plus) or a sender named with letters, any run of 4 to
  8 digits hides the message. A run counts on its own and with spaces, dots or dashes between
  its groups, so neither "31-77-90" nor "1234 5678 9012" slips past.
- From any sender, a code word with a digit on its own line, the line before or the line after
  hides the message, so "123456\nis your code" is caught too. The words are ADR-CVM-010's,
  matched in any case and inside longer words. The line before was added at the mobile
  architect's word, who is correcting ADR-CVM-010's wording to match; its red run is in
  `red-otp-line-before.txt`.
- Text is first put in Unicode's compatibility form, and a digit is any decimal digit. So
  fullwidth digits, Arabic-Indic digits and a no-break space in "logg inn" are caught.
- That form keeps invisible format characters, so a zero-width space, a soft hyphen or a word
  joiner inside a code word would have let a code through. They are dropped after it, and every
  kind of dash reads as "-", so "one‑time" with a no-break hyphen or an en dash is caught too.
  This was the mobile architect's finding; its red run is in `red-otp-hidden-chars.txt`.
  Voice-architect then found three characters outside those classes: the minus sign, the
  combining grapheme joiner and the variation selectors. They are named one by one. Their red run
  is in `red-otp-more-chars.txt`. A fourth sample pins that digits split by a zero-width space
  count as one run, so "12<ZWSP>34" from a short code is hidden. It was green from the start,
  because dropping the invisible character already joins them.
- A hidden message is replaced whole by "a one-time code from <sender>". The owner still reads it
  in the phone's own text app.

Every sample is made up. The senders are invented names, short codes and numbers in the unused
+47 00 range, and every code is invented too.

Texts with dates, prices and words that only hold a code word, such as "shopping", are listed
and counted, not judged. So are the codes the rules give up on purpose: a code with no code word
from a service number of seven or eight digits with no plus. A short code is at most six digits,
so that an ordinary mobile number written without its plus is not taken for one, at the mobile
architect's word. Gradle's test task writes the count to
`build/reports/otp-masking/listed.txt`, and CI keeps it with the run as the artifact
`otp-masking`. It is never committed. Pitest's runs of the same tests leave it alone, because
only the test task passes the report's path.

Pitest mutates the masker, and its score goes to the code-health page, never into the
repository. The first version used Kotlin's inlined `any` and `count`. Pitest's mutants in that
inlined code sat on line numbers past the file's end, where no test could reach them, and the
quality ratchet refused them. So the checks are plain loops and patterns now, each comparison a
line of the file's own. The code-word pattern is the words joined as they are, with no lambda in
the class's set-up, which Pitest cannot run again.

A rules module stays plain Kotlin and mutated only while its build file says so, so
`tests/test_rules_modules.py` reads every `plugins/*/rules` build file in the tree. Each must
apply the JVM and Pitest plugins and no Android plugin. Its red run, with the sms rules module
turned into an Android library for one run, is in `red-rules-module-pure.txt`.
