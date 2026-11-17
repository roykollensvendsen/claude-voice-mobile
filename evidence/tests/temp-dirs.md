# The file tests clean up after themselves

The red run is `red-temp-dirs.txt`: one run of `FileHubStoreTest` and `FileLogStoreTest` left 10
folders in the system's temporary folder, one per test, since each made its own with
`Files.createTempDirectory` and never deleted it.

Both now take JUnit 5's `@TempDir`, which gives each test a new folder and deletes it afterwards.
The same run, counted the same way, left 0 folders. Quality asked for this as n1 on #99.
