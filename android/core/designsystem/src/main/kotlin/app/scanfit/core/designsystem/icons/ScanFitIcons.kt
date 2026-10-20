package app.scanfit.core.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Tool glyphs for Home cards and tool screens (Material Icons shapes, Apache-2.0), drawn here because the app ships
 * only `material-icons-core` (APK size, TECH_FEASIBILITY §2). 24 × 24 viewport, filled, tinted by the caller.
 */
object ScanFitIcons {
    /** Graduation cap: exam forms. */
    val Exams: ImageVector by lazy {
        filled("Exams", "M5,13.18v4L12,21l7,-3.82v-4L12,17l-7,-3.82zM12,3L1,9l11,6 9,-4.91V17h2V9L12,3z")
    }

    /** A picture: resize photo. */
    val Photo: ImageVector by lazy {
        filled(
            "Photo",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
                "M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
        )
    }

    /** A pen stroke: signature. */
    val Signature: ImageVector by lazy {
        ImageVector.Builder("Signature", SIZE, SIZE, VIEWPORT, VIEWPORT).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null,
            ) {
                moveTo(3f, 16f)
                curveTo(5f, 9f, 8f, 5f, 9f, 8f)
                curveTo(10f, 11f, 6f, 17f, 8.5f, 17f)
                curveTo(11f, 17f, 12f, 11f, 14f, 12f)
                curveTo(15.5f, 13f, 14f, 16f, 16f, 16f)
                curveTo(17.5f, 16f, 18.5f, 14f, 21f, 14f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 19f)
                horizontalLineTo(21f)
                verticalLineTo(20.5f)
                horizontalLineTo(3f)
                close()
            }
        }.build()
    }

    /** Concentric loops: thumb impression. */
    val Thumb: ImageVector by lazy {
        ImageVector.Builder("Thumb", SIZE, SIZE, VIEWPORT, VIEWPORT).apply {
            for (r in THUMB_RINGS) {
                path(stroke = SolidColor(Color.Black), strokeLineWidth = THUMB_STROKE, fill = null) {
                    ellipse(CENTRE_X, CENTRE_Y, r * THUMB_ASPECT, r)
                }
            }
        }.build()
    }

    /** A page with lines: handwritten page. */
    val Document: ImageVector by lazy {
        filled(
            "Document",
            "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6z" +
                "M16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z",
        )
    }

    /** "PDF" on stacked pages: images to PDF. */
    val Pdf: ImageVector by lazy {
        filled(
            "Pdf",
            "M20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z" +
                "m-8.5,7.5c0,0.83 -0.67,1.5 -1.5,1.5H9v2H7.5V7H10c0.83,0 1.5,0.67 1.5,1.5v1z" +
                "m5,2c0,0.83 -0.67,1.5 -1.5,1.5h-2.5V7H15c0.83,0 1.5,0.67 1.5,1.5v3z" +
                "m4,-3H19v1h1.5V11H19v2h-1.5V7h3v1.5zM9,9.5h1v-1H9v1z" +
                "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zm10,5.5h1v-3h-1v3z",
        )
    }

    /** Arrows pressing in: compress a PDF. */
    val Compress: ImageVector by lazy {
        filled(
            "Compress",
            "M8,19h3v3h2v-3h3l-4,-4 -4,4zM16,4h-3L13,1h-2v3L8,4l4,4 4,-4z" + "M4,9v2h16L20,9L4,9zM4,12h16v2H4z",
        )
    }

    /** Two paths joining: merge PDFs. */
    val Merge: ImageVector by lazy {
        filled(
            "Merge",
            "M17,20.41L18.41,19 15,15.59 13.59,17 17,20.41zM7.5,8H11v5.59L5.59,19 7,20.41l6,-6V8h3.5L12,3.5 7.5,8z",
        )
    }

    /** A card: ID card on one page. */
    val IdCard: ImageVector by lazy {
        filled(
            "IdCard",
            "M20,4H4c-1.11,0 -1.99,0.89 -1.99,2L2,18c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2" +
                "V6c0,-1.11 -0.89,-2 -2,-2z" +
                "M9,8c1.1,0 2,0.9 2,2s-0.9,2 -2,2 -2,-0.9 -2,-2 0.9,-2 2,-2z" +
                "M13,16H5v-0.5c0,-1.33 2.67,-2 4,-2s4,0.67 4,2V16z" +
                "M19,15h-4v-1.5h4V15zM19,11.5h-4V10h4v1.5z",
        )
    }

    /** A seal with a tick: check a file. */
    val Check: ImageVector by lazy {
        filled(
            "Check",
            "M12,2L4,5v6.09c0,5.05 3.41,9.76 8,10.91 4.59,-1.15 8,-5.86 8,-10.91V5L12,2z" +
                "M10.94,15.54L7.4,12l1.41,-1.41 2.12,2.12 4.24,-4.24 1.41,1.41 -5.64,5.66z",
        )
    }

    /** A viewfinder over a page: scan (coming soon). */
    val Scan: ImageVector by lazy {
        filled(
            "Scan",
            "M7,3H4c-0.55,0 -1,0.45 -1,1v3h2V5h2V3zM20,3h-3v2h2v2h2V4c0,-0.55 -0.45,-1 -1,-1z" +
                "M5,17H3v3c0,0.55 0.45,1 1,1h3v-2H5v-2zM19,19h-2v2h3c0.55,0 1,-0.45 1,-1v-3h-2v2zM7,7h10v10H7V7z",
        )
    }

    /** A printer: printable guide sheet (coming soon). */
    val Print: ImageVector by lazy {
        filled(
            "Print",
            "M19,8H5c-1.66,0 -3,1.34 -3,3v6h4v4h12v-4h4v-6c0,-1.66 -1.34,-3 -3,-3zM16,19H8v-5h8v5z" +
                "M19,12c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1zM18,3H6v4h12V3z",
        )
    }

    private val SIZE = 24.dp
    private const val VIEWPORT = 24f
    private val THUMB_RINGS = listOf(3f, 6f, 9f)
    private const val THUMB_STROKE = 1.8f
    private const val THUMB_ASPECT = 0.8f
    private const val CENTRE_X = 12f
    private const val CENTRE_Y = 13f

    private fun filled(
        name: String,
        pathData: String,
    ): ImageVector = ImageVector.Builder(name, SIZE, SIZE, VIEWPORT, VIEWPORT)
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()

    private fun PathBuilder.ellipse(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
    ) {
        moveTo(cx - rx, cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + rx, y1 = cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - rx, y1 = cy)
        close()
    }
}
