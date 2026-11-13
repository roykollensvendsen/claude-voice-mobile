# Design note: saying plainly when Tailscale is off

Decided 2026-11-13 by the hub session, within its mandate: no new permission
and no protocol change. It is built as part of the core's tailnet listener,
before the earbuds.

The phone answers the hub only on its tailnet address. When that address is
gone, the app says what is wrong and what to do, in its always-shown
notification and on its screen.

- **No VPN on the phone:** "Tailscale looks like it's off on this phone. Start it, and the hub can reach the phone again." A button opens the Tailscale app, or its download page if it isn't installed.
- **A VPN without a tailnet address:** "Another VPN seems to be on. Tailscale can't run beside it. Turn that VPN off, and start Tailscale." It is a guess, so it names no VPN.
- **On the tailnet, but the hub can't be reached:** "The phone is on the tailnet, but the hub isn't answering. Is the laptop on and awake?"
- The test is the phone's own tailnet address, looked for only on the VPN network (IPv4 100.64.0.0/10, or IPv6 fd7a:115c:a1e0::/48). Mobile networks also hand out 100.64 addresses, so an address on any other network says nothing. A VPN alone is not enough either, because another VPN would look the same.
- "The hub isn't answering" comes from the phone's own traffic with the hub, or from a slow check. The phone never asks often enough to keep the radio awake. It needs no new permission and no protocol change.
- **Order:** part of the core's tailnet listener, before the earbuds. Without the tailnet, nothing else on the phone works.

## Notes for building it

- Watch the network with `registerDefaultNetworkCallback`, or with a request
  that drops `NET_CAPABILITY_NOT_VPN`; a plain request never sees the VPN.
  Read the addresses in `onLinkPropertiesChanged`, because an address can come
  after the network.
- Opening Tailscale needs `<queries><package android:name="com.tailscale.ipn"/></queries>`
  and `getLaunchIntentForPackage`, with the download page when it returns null.
