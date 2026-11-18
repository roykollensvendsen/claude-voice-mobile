# A plugin name the protocol would refuse stops the build

The red run is in `red-plugin-name-pattern.txt`: `ModulePicks` took any name that matched a
folder under `plugins/`.

A plugin's folder, package and protocol name are one string, and the agent protocol takes a
plugin name only when it matches `^[a-z][a-z0-9_]{0,31}$`. A folder such as `Sms-2` used to
build, and its plugin would only be refused by the hub. Now `-PcvPlugins` refuses such a name
when the build starts, even when its folder exists:

    cvPlugins names Sms-2, which is not a plugin name: a small letter, then at most 31 small
    letters, digits or _

A name of 32 characters is taken; one of 33 is refused.
