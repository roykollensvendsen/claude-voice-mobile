# Every Gradle wrapper is Gradle's own

The red run is in `red-gradle-wrapper.txt`: the repository's `gradlew`, and the audio-focus
prototype's copy of it, were not Gradle's script. `gradlew.bat` was missing.

The script that starts Gradle had been written by hand to look like Gradle's. Its comments were
odd, and it lacked the real script's careful handling of arguments. The wrapper jar was
genuine: its checksum matched Gradle 8.11.1's.

Now:

- `gradlew` and `gradlew.bat` are what Gradle 8.11.1's own wrapper task writes. That task was run
  through the genuine jar, so the look-alike script played no part. The same two files are in
  `prototype/audio-focus/`.
- They were compared with the copies in Gradle's repository at tag `v8.11.1`:
  <https://github.com/gradle/gradle/blob/v8.11.1/gradlew>,
  <https://github.com/gradle/gradle/blob/v8.11.1/gradlew.bat> and
  <https://github.com/gradle/gradle/blob/v8.11.1/gradle/wrapper/gradle-wrapper.jar>. The jar's
  checksum there is the one pinned here. The only
  difference is a default Java option that Gradle's own build sets for itself
  (`-Dfile.encoding=UTF-8`), plus the batch file's line endings. The wrapper task's output is
  what Gradle publishes for projects.
- `gradle/wrapper/gradle-wrapper.properties` is unchanged; the task's rewrite of its network
  timeout was put back.
- `tests/test_gradle_wrapper.py` finds every wrapper in the repository by walking it, and pins
  each one's `gradlew`, `gradlew.bat` and jar by checksum. A prototype added later is checked
  without being listed.
- `.gitattributes` keeps the scripts' line endings, so a checkout on any machine gives the pinned
  bytes.

`./gradlew check` passes through the new script.
