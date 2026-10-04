package app.scanfit.core.match

import app.scanfit.core.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Test

/** ALGORITHMS 9.5: the kind each slot type is matched and export-checked as. */
class DocKindTest {
    @Test
    fun everySlotTypeMapsToItsKind() {
        val expected =
            mapOf(
                DocType.PHOTO to DocKind.PHOTO,
                DocType.POSTCARD_PHOTO to DocKind.PHOTO,
                DocType.SIGNATURE to DocKind.SIGNATURE,
                DocType.TRIPLE_SIGNATURE to DocKind.SIGNATURE,
                DocType.LEFT_THUMB to DocKind.THUMB,
                DocType.THUMB_IMPRESSION to DocKind.THUMB,
                DocType.LEFT_HAND_FINGERS_THUMB to DocKind.FINGERS,
                DocType.RIGHT_HAND_FINGERS_THUMB to DocKind.FINGERS,
                DocType.HANDWRITTEN_DECLARATION to DocKind.DECLARATION,
                DocType.CLASS10_CERTIFICATE to DocKind.PDF_DOCUMENT,
                DocType.ID_PROOF to DocKind.PDF_DOCUMENT,
            )
        for ((type, kind) in expected) assertEquals(type.name, kind, DocKind.of(type))
    }
}
