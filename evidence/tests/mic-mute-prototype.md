# The mic-mute test app

The red runs are in `red-mic-mute-prototype.txt`: the test app's decision code and the laptop
script were stubs that decided nothing.

ADR-CVM-008 measures, before anything is built on it, whether Android's phone-wide microphone
mute stops every sound reaching the Claude app. The test app in `prototype/mic-mute/` runs
those measurements. What decides a pass or a fail is tested off the phone:

- a steady tone is not silence however often it crosses zero. Silence is a run of zero
  samples, found where it began even across recording buffers;
- a second tap within 700 ms is ignored;
- the 95th percentile is by nearest rank;
- an unmute seen while the app holds the mute is a leak, even one put right at once, and an
  unmute the app asked for is not;
- a word schedule mutes a third to two thirds of its slots, through the whole slot;
- an M1b reading passes only with exactly one capture, silenced;
- the mute tone falls and the unmute tone rises;
- on the laptop: made-up words, said in the middle of each slot from the same whole minute as
  the phone, and M1c passes only when no muted word and every unmuted word reaches the
  transcript.

Tried on an Android 15 emulator, by its screen's buttons:

- with the stand-in open, it heard non-zero samples. Muted, it heard none, and silence began
  34 and 51 ms after the tap;
- a media play/pause key reached the session as an earbud tap and toggled the mute;
- a word schedule muted exactly the slots of its pattern, from the whole minute;
- with no capture active, M1b failed with "0 captures" rather than guessing.

That first run found two faults, fixed before this commit:

- the watch saw the moment before the mute applied as a leak;
- the samples before silence began, which are M4's delay, were counted against M1a.

The emulator proves the app runs. It proves nothing about the owner's phone, which is what the
measurements are for.
