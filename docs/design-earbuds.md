# Design note: controlling the earbuds by voice

Proposed 2026-11-09 by the Android developer session. Nothing is built until
Roy says yes, and the architect has placed it in ADR-CVM-003.

## What Roy wants

Roy jogs with Soundcore P40i earbuds while talking to Claude. He wants to say
"turn on noise cancelling", "transparency", "what's the battery on my
earbuds?" and have it done. Today that takes the Soundcore app and both hands.

What the earbuds can be asked for:

- **Sound mode:** noise cancelling, transparency (hearing traffic) or normal.
- **Equalizer:** choose a preset.
- **Battery:** left, right and the case.
- **Connected or not.**

## How it fits the app

It becomes one more plugin, `earbuds`, beside `status`, `playback` and the
rest. It follows the layer rules: it depends only on `plugin-api`, and no
other plugin depends on it.

The hub reaches the phone the usual way. Roy speaks to the Claude app, Claude
calls the hub, and the hub calls the plugin on the phone over the tailnet.
Nothing new is needed in protocol version 1. A plugin offers named tools with
a JSON schema for their inputs, and answers with a structured answer or a
refusal said in words.

Proposed tools:

| Tool | Takes | Answers |
|---|---|---|
| `earbuds_status` | nothing | connected or not, the model, the sound mode, the battery of each bud and the case |
| `earbuds_set_sound_mode` | `noise_cancelling`, `transparency` or `normal` | the mode now set |
| `earbuds_set_equalizer` | a preset name | the preset now set, or a refusal naming the presets there are |

A refusal says in plain words what to do: "the earbuds are not connected",
"the Soundcore app is holding the earbuds, close it".

None of these settings are on the list the hub may never change (microphone,
location logging, the direct-call list, the camera). Every change is undone
by saying the opposite. So no spoken yes is needed per call, only the usual
approval of the plugin once.

## Talking to the earbuds

Soundcore earbuds take commands over a Bluetooth serial link (RFCOMM), beside
the sound itself. The commands are not published. OpenSCQ30
(github.com/Oppzippy/OpenSCQ30) has worked them out for many models, the P40i
among them (model A3955), and has an Android app. We reuse its work rather
than work the protocol out again.

There are two ways to reuse it:

- **Use its library as it is.** The library is written in Rust and reaches
  Android through generated bindings. We would get every fix upstream makes.
  But CI and every build would need Rust and Android's native toolkit, the
  app would carry native code for each phone chip, and the library covers
  hundreds of devices we do not need.
- **Port the P40i part to Kotlin.** *Recommended.* That means the packet
  framing, the checksum, and the P40i's sound modes, equalizer and battery,
  a small part of OpenSCQ30. The plugin stays a plain Kotlin module like
  the others. OpenSCQ30's recorded example bytes become our tests. The cost
  is that fixes upstream must be carried over by hand. A note in the module
  says which OpenSCQ30 version it follows.

## Licence

OpenSCQ30 is GPL-3.0-or-later, and so is this app. Porting its code here is
allowed. The ported files keep its copyright notice, say they are derived from
it, and stay under the GPL. Nothing else is required.

## What to measure first

These are open questions until tried on the phone:

- whether the serial link works while Claude's call holds the earbuds' call
  link, which matters most when jogging;
- whether the Soundcore app, if installed, holds the serial link and blocks
  ours;
- that the plugin needs Android's permission to connect to Bluetooth devices
  (Android 12 and later), asked for once in the app.

A small throwaway check, like the audio-focus prototype, could answer the first
two before the plugin is built. Or OpenSCQ30's own Android app answers them
with no code at all: if it can switch the mode during a Claude call, so can we.

## Where it goes in the order

ADR-CVM-003 builds in this order:

1. the audio-focus prototype, nearly done;
2. `core`, `plugin-api` and `status`, on a frozen protocol version;
3. each plugin in the order ADR-CV-007 lists.

Proposed: `earbuds` comes straight after `status`, as the first plugin built
in step 3. It is small and does not depend on the playback work. It is also
used on every run. It cannot come earlier, because nothing can reach the
phone before step 2. Adding a plugin and changing the order is a change to
ADR-CVM-003, so it is the architect's to write and Roy's to approve.
