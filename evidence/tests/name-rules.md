# Once the phone has a name, it keeps it; a lost name is taken again

The red run is `red-name-rules.txt`: `Confirmed.AlreadyNamed`, `NameAnswer.shown` and
`NameAnswer.ends` did not exist yet, and `MachineName` still needed to know whether a hub was
paired.

Three notes on #108:

- **claude-voice-mobile-architect (1).** While unpaired, the name could still change, but the
  agent was already built under the first name. So the hub could pair as "a" while
  `machine.json` said "b". Now a different name is refused once one is kept, paired or not.
  Changing it means clearing the app's data.
- **claude-voice-mobile-architect (2) and quality f1.** If `hub.json` survived and
  `machine.json` was lost, every confirm answered "paired under another name", and the phone was
  stuck. Now, with no name kept, a name is taken again even while paired. This opens nothing,
  because the hub does not trust the name `agent_info` gives: it knows the phone by its tailnet
  node and its pairing key, and calls it by a name of its own (voice-architect, on #109). With
  that, `MachineName` no longer needs to know about the pairing at all.
- **quality n1.** The screen's two rules, shown only while the phone has no name and ended only
  by a kept name, are now `NameAnswer.shown` and `NameAnswer.ends`, with tests. `NameStep` is
  left as wiring.

The tests:
- once a name is kept, a different one is refused and the kept one stays, while the same name is
  still kept;
- a phone whose name file was lost takes a name again;
- the step shows only with no name, and only `Kept` ends it.
