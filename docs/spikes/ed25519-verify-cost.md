# Finding: Ed25519 verification cost on low-RAM Android

**Why it matters.** The signed presets bundle is verified on load (ROADMAP Phase 0). minSdk is 26, but the platform
`Signature.getInstance("Ed25519")` only exists from API 33, so the app uses Tink's pure-Java Ed25519 (ARCHITECTURE section 6).
The exam list (Phase 2) will wait on this call.

**Measured** on the Android emulator `Medium_Phone_API_35` (arm64, `-memory 2048`), 71 KB bundle, 55 exams.
This is an emulator on an Apple-silicon host, **not** a physical low-end phone: treat it as an order of magnitude.

| Build | Ed25519 verify | JSON decode | Whole `loadEmbedded()` |
|---|---|---|---|
| Debug (no AOT, no R8) | ~5,300 ms | n/a | ~6,000 ms |
| Release (R8), cold JIT, 3 runs | 662 / 1,137 / 1,366 ms | 17-52 ms | 748-1,452 ms |
| Release + verified-digest cache, launches 3-4 | skipped | ~30 ms | 253-285 ms |

Cause (not profiled): Tink builds large precomputed curve tables in static initialisers; decode is negligible.
iOS is unaffected (CryptoKit is native).

**Mitigation shipped.** `VerifiedDigestStore`: after a full verification, the SHA-256 of (bundle, signature, public key) is
remembered in no-backup storage; a launch with the identical triple skips the Ed25519 step. Any changed byte misses the cache
and is fully verified (tests: `VerifiedDigestStoreTest`). The first launch after install or update still pays ~1-2 s.

**Open.**
- Re-measure on a real 4 GB / API 29-30 phone (spike 4).
- Phase 5: Baseline Profile covering the verify path; consider the platform provider on API 33+.
- The per-process cost remains for the first launch of every app version and for every OTA bundle (Phase 4); OTA verification
  runs in the background worker, so it does not block the UI.
