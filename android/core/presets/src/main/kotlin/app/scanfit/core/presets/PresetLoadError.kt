package app.scanfit.core.presets

/** Why a preset bundle was refused. `reason` matches the `sync_failure_reason` analytics enum and cases.json. */
enum class PresetLoadError(
    val reason: String,
) {
    BAD_SCHEMA("bad_schema"),
    STALE_VERSION("stale_version"),
    PARSE("parse"),
}
