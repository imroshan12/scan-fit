# Spike 5: conformance in CI: Robolectric native graphics

**Question.** Does Robolectric in native-graphics mode give *real* Skia JPEG encode/decode on the JVM, so `:core:imaging` conformance
can run in ordinary unit tests instead of an emulator job? (TECH_FEASIBILITY section 3, spike 5.)

**Setup.** Robolectric 4.17, `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, `@Config(sdk = [35])`, AGP 9.4.1, no emulator.

**Result: the JVM half is answered, yes.**
- A 200×230 noisy bitmap compressed through `Bitmap.compress(JPEG, q)` produced **7,583 / 14,605 / 39,431 bytes at q = 35 / 65 / 95**: monotonic,
  plausible, and decoded back to 200×230 with `BitmapFactory`.
- The production `AndroidJpegEncoder` / `AndroidImageDecoder` and the Canvas text renderer (`AndroidStripRenderer`) all run under it.
  `FitConformanceTest` (all `fit_cases`) and the 10 shared `decode_cases` (all eight EXIF orientations) pass on the JVM.
- Cold start of the Robolectric sandbox is the main cost; the whole `:core:imaging` suite (≈70 tests) runs in well under a minute.

**Still open: the "±10% of a real device" half.** Nothing here compares those byte sizes to what an Android device's encoder produces.
The conformance cases assert *windows*, not exact sizes, and the fit search measures whatever the encoder actually returns, so a size
difference changes which `q` is chosen, not whether the result is valid. To close this: run the same encode at q = 35 / 65 / 95 in an
instrumented test on the emulator (and one real phone) and compare. If the difference exceeds 10%, keep Robolectric for logic and add an
emulator conformance job for the size-sensitive cases.

**Decision (provisional).** Run conformance on the JVM in CI (`android.yml`). Revisit after the device comparison.
