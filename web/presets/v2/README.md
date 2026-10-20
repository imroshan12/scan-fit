# /presets/v2/

Served to the apps (ARCHITECTURE section 6). **Do not edit by hand and do not commit the bundle.**
`.github/workflows/pages.yml` runs `spec/tools/build_presets.py` on every deploy and copies `spec/dist/presets.json`
here, so the site always carries the presets of the commit it was built from.

Apps fetch `GET /presets/v2/presets.json` with `If-None-Match` over HTTPS, run the same format checks as for the
embedded copy (spec/README.md "Loading presets": JSON header, `schema_version` ≤ 2, `presets_version` strictly newer,
full decode) and keep their current presets on any failure. There is no signature (user decision 2026-10-20).
`v2` is the bundle `schema_version`; a schema bump publishes under `/presets/v3/` and leaves `v2` serving old apps.
