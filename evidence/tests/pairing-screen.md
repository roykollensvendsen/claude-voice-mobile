# The phone listens for the hub and shows a pairing code

The red run is in `red-pairing-screen.txt`.

Before this change, the app had all the parts for the hub to pair with it and call it: the
listener, the pairing code and the MCP handler. Nothing started them, and the app had no
permission to use the network, so the hub could not reach the phone at all. This change wires
them up, as ADR-CVM-003 describes the one foreground service. The rest of that service waits
for #78 and #58: starting again after a restart, a switch to turn it off, and the line shown
when notifications are refused.

## What the tests pin, off the phone

The one new decision is in the core, in `PairingOffer`, and `PairingOfferTest` covers it:

- The screen offers pairing only while the phone listens. It then gives the address the hub
  adds: 192.0.2.10 in the test, an address set aside for documentation.
- While the phone listens nowhere, no pairing is offered.
- A code stays on the screen only while it still works. It leaves once it has run out, and as
  soon as it has paired.

Pitest on the core killed every mutant in the new code. One survivor remains, in `KeptGate`,
which this change does not touch.

## What the emulator showed

The emulator ran Android 15, with a build of this branch that held the `status` and `sms`
plugins, after a fresh install.

- At first start the app asked once to show notifications. That was allowed.
- After the suggested name was confirmed, the listener service ran in the foreground with the
  special-use type, and its notice was on the listener channel.
- The app did not crash.
- The emulator is not on a tailnet. So the screen said Tailscale looks off, and the pairing
  button stayed hidden, as it should.

## Not shown yet

The pairing itself needs a phone on the owner's tailnet: the button, the code, the hub's
`machines approve`, and a call. That is the owner's test tonight (#145). While a code shows,
the window is marked secure, so the code never reaches a screenshot or the recent-apps
picture. It is never logged.
