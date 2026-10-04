package app.scanfit.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors spec/schema/exam.schema.json (schema v2). Exams are data: never hard-code a rule in app code
// (CLAUDE.md rule 5). If the schema cannot express a rule, extend the schema and the validator first.

@Serializable
enum class ExamCategory {
    @SerialName("banking")
    BANKING,

    @SerialName("insurance")
    INSURANCE,

    @SerialName("regulator")
    REGULATOR,

    @SerialName("ssc")
    SSC,

    @SerialName("upsc")
    UPSC,

    @SerialName("railway")
    RAILWAY,

    @SerialName("defence")
    DEFENCE,

    @SerialName("teaching")
    TEACHING,

    @SerialName("state_psc")
    STATE_PSC,

    @SerialName("entrance")
    ENTRANCE,
}

@Serializable
enum class ExamStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("hidden")
    HIDDEN,

    @SerialName("retired")
    RETIRED,
}

@Serializable
enum class Confidence {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW,
}

@Serializable
enum class SourceKind {
    @SerialName("official")
    OFFICIAL,

    @SerialName("secondary")
    SECONDARY,
}

@Serializable
enum class DocType {
    @SerialName("photo")
    PHOTO,

    @SerialName("postcard_photo")
    POSTCARD_PHOTO,

    @SerialName("signature")
    SIGNATURE,

    @SerialName("triple_signature")
    TRIPLE_SIGNATURE,

    @SerialName("left_thumb")
    LEFT_THUMB,

    @SerialName("thumb_impression")
    THUMB_IMPRESSION,

    @SerialName("left_hand_fingers_thumb")
    LEFT_HAND_FINGERS_THUMB,

    @SerialName("right_hand_fingers_thumb")
    RIGHT_HAND_FINGERS_THUMB,

    @SerialName("handwritten_declaration")
    HANDWRITTEN_DECLARATION,

    @SerialName("photo_id")
    PHOTO_ID,

    @SerialName("id_proof")
    ID_PROOF,

    @SerialName("class10_certificate")
    CLASS10_CERTIFICATE,

    @SerialName("category_certificate")
    CATEGORY_CERTIFICATE,

    @SerialName("pwd_certificate")
    PWD_CERTIFICATE,

    @SerialName("sc_st_certificate")
    SC_ST_CERTIFICATE,
}

@Serializable
enum class FileFormat {
    @SerialName("jpg")
    JPG,

    @SerialName("jpeg")
    JPEG,

    @SerialName("png")
    PNG,

    @SerialName("pdf")
    PDF,
}

@Serializable
enum class DimensionMode {
    /** Must match exactly. */
    @SerialName("exact")
    EXACT,

    /** Aim for it; the portal tolerates others. */
    @SerialName("preferred")
    PREFERRED,

    /** A min/max box (and optional aspect range). */
    @SerialName("range")
    RANGE,

    /** Unspecified. */
    @SerialName("none")
    NONE,
}

@Serializable
data class SizeKb(
    val min: Double? = null,
    val max: Double? = null,
    val target: Double? = null,
)

@Serializable
data class AspectRange(
    val min: Double? = null,
    val max: Double? = null,
)

@Serializable
data class Dimensions(
    val mode: DimensionMode,
    val width: Int? = null,
    val height: Int? = null,
    val minW: Int? = null,
    val minH: Int? = null,
    val maxW: Int? = null,
    val maxH: Int? = null,
    val aspectWOverH: AspectRange? = null,
)

@Serializable
data class PhysicalSize(
    val w: Double? = null,
    val h: Double? = null,
)

@Serializable
data class NameDateStrip(
    val required: Boolean? = null,
    val verified: Boolean? = null,
    val note: String? = null,
)

@Serializable
data class DocSpec(
    val type: DocType,
    val required: Boolean,
    val formats: List<FileFormat>,
    val sizeKb: SizeKb,
    val dimensions: Dimensions,
    val dpi: Int? = null,
    val physicalCm: PhysicalSize? = null,
    /** Base name without extension, if the portal expects one (NTA: "Photograph"). */
    val filename: String? = null,
    val nameDateStrip: NameDateStrip? = null,
    /** Verbatim text from the official notice. `null` = not yet transcribed (UI must say "copy from notice"). */
    val declarationText: String? = null,
    val notes: String? = null,
    val rules: List<String> = emptyList(),
)

@Serializable
data class Source(
    val url: String,
    val kind: SourceKind,
)

@Serializable
data class Exam(
    val id: String,
    val name: String,
    /** Other spellings and Hindi forms, used only by search (ALGORITHMS §10). */
    val aliases: List<String> = emptyList(),
    val body: String,
    val category: ExamCategory,
    val portal: String? = null,
    /** `true` = the portal captures a live photo; `null` = not stated in sources. */
    val livePhotoCapture: Boolean? = null,
    val status: ExamStatus,
    val documents: List<DocSpec>,
    val specialRules: List<String>,
    val sources: List<Source>,
    val confidence: Confidence,
    val confidenceNote: String,
    /** ISO date (yyyy-MM-dd). */
    val lastVerified: String,
) {
    /** Low-confidence presets must show the "Unverified" badge (CLAUDE.md rule 6). */
    val isUnverified: Boolean get() = confidence == Confidence.LOW
}
