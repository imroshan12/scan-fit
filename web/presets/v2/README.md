# /presets/v2/

Served to the apps (ARCHITECTURE section 6). **Do not edit by hand and do not commit the bundle.**
`.github/workflows/spec.yml` runs `spec/tools/build_all.sh --release` on `main`, then copies
`spec/dist/presets.json` and `spec/dist/presets.json.sig` here before deploying the site.

Apps fetch `GET /presets/v2/presets.json` with `If-None-Match`, then `presets.json.sig`, verify Ed25519 against
the embedded public key, and refuse any bundle whose `presets_version` is not strictly newer.
`v2` is the bundle `schema_version`; a schema bump publishes under `/presets/v3/` and leaves `v2` serving old apps.
