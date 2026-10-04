# feature:flow-ink

The ink flows (ROADMAP Phase 2, ALGORITHMS §3 and §9.5): signature (incl. triple), thumb, NEET fingers and the handwritten
declaration. Pick (camera, gallery, Files) → free rectangular crop → cleanup (signature / thumb / document variant) → pad to
aspect + fit → review (Crisp black, Darker ink, quality warning, one-time handwriting confirmation for signatures) → save.

- `InkFlowViewModel` holds the steps as an immutable `InkUiState`; image work runs off the main thread.
- `InkTools` is the platform boundary (reading the picked file, decoding, cleanup + fit); the view-model tests use a fake.
- Saving goes through the shared `DocumentExporter` in `:core:data`, checked as the slot's match kind (§1.6, §9.5).
- Rectification (4-corner warp) comes with the document scanner; until then the crop is an axis-aligned rectangle.
