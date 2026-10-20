package app.scanfit.feature.home

/** What a Home card opens (UI_UX §3 Home). The app maps each to a route, since features never import each other. */
enum class HomeTool {
    EXAMS,
    PHOTO,
    SIGNATURE,
    COMPRESS_PDF,
    IMAGES_TO_PDF,
    MERGE_PDFS,
    ID_CARD,
    CHECK,
    THUMB,
    DOCUMENT,
}
