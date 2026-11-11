# Design note: location triggers

Proposed 2026-11-11 by the architect session. The bridge side is the hub
session's. Nothing is built until Roy says yes.

## What Roy wants

When he comes within a set distance of a place he has saved, such as home, the
phone tells the bridge, and the bridge **asks** him whether to do something:
"You're nearly home. Open the garage door?" It acts only on his yes. Location
alone never opens anything. Places, distances, actions and an on/off switch
are set per trigger. It works with the phone in the car.

## Scope

- **On the phone, a `location` plugin with triggers.** It knows the places and
  their distances, and switches each trigger on or off. It notices arriving
  inside a place's distance, not leaving it, with a margin so that standing at
  the edge does not trigger again and again.
- **To the hub, an event and nothing else:** the place's id and `arriving`.
  Never a position, never a distance, never a time series.
- **On the hub, the bridge asks.** It asks through the request-and-confirm flow
  its plugins already have, such as the garage door's. Which actions a place
  may ask about is a short list per place, set by typing on the laptop.

## Privacy

- **Places stay on the phone.** They are among the settings only the phone
  changes: no tool may take a place, a distance or a trigger as an input. So
  the hub, a plugin or the voice can never set a place to follow someone.
- **Nothing is logged over time.** A trigger needs Android's background
  location permission, which the app asks for only when the first trigger is
  switched on. The phone keeps no positions. The location-logging setting is
  not needed for this, and it stays off unless switched on.
- **The plugin declares `location` in `uses`**, so Roy sees it when he approves
  the plugin. The call log keeps that a trigger fired, never where.
- **One switch turns off every trigger.** It sits in the app and in its
  notification.

## Safety

- **A trigger never acts.** It can only make the bridge ask. A place's actions
  can only be tools that ask for a yes before acting. A tool that acts at once
  cannot be on the list.
- **The question expires in 60 seconds.** A repeat of the same trigger within
  10 minutes asks nothing new.
- **The garage door's rules still hold.** The voice never arms it, nothing
  presses without a yes, and no magic phrase works through a trigger.
- **Every trigger and every answer** goes into the bridge's change log.
- **In the car:** the question is spoken when the voice is running. It is
  never something to read and tap while driving.

## Open for Roy

- **A yes from the notification.** When the voice is not running, the phone
  could show the question as a notification with Yes and No. A tap is a live
  yes from Roy on his own phone. But protocol version 1 lets only the voice
  confirm, so this would be a version 2 rule. The alternative is to ask only
  by voice, and skip the question when the voice is not running.

## What it needs first

- **Protocol version 2:** a way for the phone to tell the hub that something
  happened. In version 1 only the hub calls. The event carries the trigger,
  and the same key and identity checks as every request. This is listed in
  claude-voice `docs/agent-protocol/next-version.md`.
- **The settings screen**, where places are saved.
- **The `location` plugin** of ADR-CVM-003.

## Where it goes in the order

After the earbuds, the settings screen and the microphone mute, and after the
`location` plugin. It is the first function that needs version 2. Saying yes
adds it to ADR-CVM-003, which the architect writes.
