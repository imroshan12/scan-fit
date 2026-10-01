# spec/signing — preset bundle signing keys

The presets bundle (`spec/dist/presets.json`) is signed with Ed25519. Both apps embed a **public** key
and refuse any bundle (embedded or downloaded) whose signature does not verify.

| File | Purpose |
|---|---|
| `dev_public_key.b64` | Public half of the **DEV** key. Its private seed is derived from a public string in `tools/make_signing_vector.py`, so anyone can sign with it. Used by debug builds, local `build_all.sh` and the test vector. **Never ship.** |
| `prod_public_key.b64` | Public half of the real key. **Does not exist yet** (see below). Release builds embed this and fail if it is missing. |

## Creating the production key (once, before the first release)
```bash
# once, from the repo root (macOS Python is externally managed, so a virtual environment is required; .venv/ is git-ignored)
python3 -m venv .venv && source .venv/bin/activate
pip install -r spec/tools/requirements.txt

python3 spec/tools/build_presets.py --genkey
```
1. Store the printed **PRIVATE** value as the GitHub Actions secret `PRESETS_SIGNING_KEY`. Keep an offline backup:
   losing it means shipping a new app build with a new public key.
2. Write the printed **PUBLIC** value (one line, base64) to `spec/signing/prod_public_key.b64` and commit it.
3. `.github/workflows/spec.yml` signs with the secret; the release builds embed `prod_public_key.b64`.

Check what is still missing at any time with `python3 spec/tools/release_preflight.py` (it never prints a secret). Release builds
also refuse the DEV key even if it is copied into `prod_public_key.b64` (Android embed task, iOS embed script, preflight).
Pull-request CI builds the release configuration with a throwaway key (`tools/rehearsal_key.sh`, CI only); that key is never published.
The full release walk-through is `docs/RELEASE_CHECKLIST.md`.

Rotating the key requires an app release (the key is compiled in). Plan an overlap if you ever need to.

## What the loaders check (`fixtures/signing/vector.json`)
signature (Ed25519 over the exact bytes, base64 text trimmed) → header parse → `schema_version` ≤ 2 →
`presets_version` strictly greater than the installed one → full decode. Failures map to
`bad_signature | parse | bad_schema | stale_version` (the `sync_failure_reason` analytics enum).
