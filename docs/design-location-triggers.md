# Design note: location triggers

Proposed 2026-11-11 by the architect session. The bridge side is the hub
session's. **Decided 2026-11-15:** the owner said yes, with option a below,
relayed by the hub session. It is built in the order at the end.

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

## Decided: only a spoken yes

The owner chose option a on 2026-11-15:

- **Near a saved place, the hub asks by voice,** for example "Open the garage
  door?", and acts only on the owner's spoken yes.
- **A tap on a notification never counts as a yes.** The phone shows no Yes
  and No buttons for a trigger.
- **When the voice is not running, nothing is asked.** The trigger is dropped,
  and only the bridge's change log records it, on the hub's own machine. It
  goes nowhere else.
- **The places stay on the phone.** Only "arriving at place X" reaches the hub.

The option not taken was a tap on the phone's notification counting as a yes,
on an unlocked phone and never for a door or a lock. It would need a protocol
version 2 rule. It is not planned. Asking again would need the owner's yes.

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
adds it to ADR-CVM-003, which the architect writes. The owner's yes is
recorded there as an amendment.
