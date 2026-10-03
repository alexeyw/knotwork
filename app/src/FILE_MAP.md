# Directory Map: src/app/src

- `androidTest/` - Instrumented tests that run on a physical or emulated Android device.
- `debug/` - `debug` build type only: the trigger-journal dump receiver. Absent from a release build, which is why a release has no dump path.
- `foss/` - `foss` distribution flavour: the no-op crash-reporting implementation, zero Firebase/Google dependency.
- `full/` - `full` distribution flavour (Play / direct APK): the Firebase Crashlytics implementation.
- `main/` - The main source code, resources, and manifest for the Android application.
- `test/` - Local unit tests that run on the JVM without an Android device. `test/golden/` holds the committed golden run traces (`traces/`) and the test-only pipelines that reach node types no shipped pipeline uses (`fixtures/`); both are compared by `GoldenTraceTest` and rewritten only with `-PrecordGoldenTraces`.
- `testFoss/` - Unit tests compiled only into the `foss` flavour.
- `testFull/` - Unit tests compiled only into the `full` flavour.
- `FILE_MAP.md` - This file mapping the current directory structure.