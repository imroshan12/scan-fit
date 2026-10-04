package app.scanfit.core.imaging

import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.Exam

/** Export file names (ALGORITHMS 1.7 / 9.8). The folder (`Downloads/ScanFit/<Exam>/`) is chosen by the export layer. */
object ExportNaming {
    private const val BYTES_PER_KB = 1024.0
    private val SEPARATORS = Regex("[^A-Za-z0-9]+")

    /** `IBPS PO / MT` -> `IBPS-PO`. */
    fun examShort(name: String): String {
        val cut = name.substringBefore(" / ").substringBefore(" (")
        return cut.replace(SEPARATORS, "-").trim('-')
    }

    private fun idOf(type: DocType): String = when (type) {
        DocType.PHOTO -> "photo"
        DocType.POSTCARD_PHOTO -> "postcard_photo"
        DocType.SIGNATURE -> "signature"
        DocType.TRIPLE_SIGNATURE -> "triple_signature"
        DocType.LEFT_THUMB -> "left_thumb"
        DocType.THUMB_IMPRESSION -> "thumb_impression"
        DocType.LEFT_HAND_FINGERS_THUMB -> "left_hand_fingers_thumb"
        DocType.RIGHT_HAND_FINGERS_THUMB -> "right_hand_fingers_thumb"
        DocType.HANDWRITTEN_DECLARATION -> "handwritten_declaration"
        DocType.PHOTO_ID -> "photo_id"
        DocType.ID_PROOF -> "id_proof"
        DocType.CLASS10_CERTIFICATE -> "class10_certificate"
        DocType.CATEGORY_CERTIFICATE -> "category_certificate"
        DocType.PWD_CERTIFICATE -> "pwd_certificate"
        DocType.SC_ST_CERTIFICATE -> "sc_st_certificate"
    }

    fun fileName(
        exam: Exam,
        slot: DocSpec,
        width: Int,
        height: Int,
        bytes: Int,
    ): String = fileName(exam.name, slot, width, height, bytes)

    fun fileName(
        examName: String,
        slot: DocSpec,
        width: Int,
        height: Int,
        bytes: Int,
    ): String {
        slot.filename?.let { return "$it.jpg" }
        val kb = roundHalfUp(bytes / BYTES_PER_KB)
        return "${idOf(slot.type)}_${examShort(examName)}_${width}x${height}_${kb}kb.jpg"
    }
}
