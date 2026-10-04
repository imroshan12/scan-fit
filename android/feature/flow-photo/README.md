# feature:flow-photo

The photo flow (ROADMAP Phase 2, ALGORITHMS §2 and §9.6): pick or capture → face check → aspect-locked crop (move, zoom,
rotate) → white background and name/date strip → fit → review.

- `PhotoFlowViewModel` holds the steps as an immutable `PhotoUiState`; all image work runs off the main thread.
- `PhotoTools` is the platform boundary (reading the picked file, decoding, drawing the strip, encoding). `AndroidPhotoTools`
  implements it; the view-model tests use a fake.
- Camera: the system camera app via `TakePicture` into a `FileProvider` file in the cache, deleted right after it is read.
  No camera or storage permission is requested. Gallery: the Photo Picker. Files: the system document picker
  (`OpenDocument`, images only).
