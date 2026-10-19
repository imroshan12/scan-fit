package app.scanfit.core.imaging

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs every `page_cleanup_cases` entry (ALGORITHMS 9.10 page cleanup): a parametric photographed page (shaded paper,
 * text bars, a dark photo block, a red stamp) must come out with white paper, dark text, the block not washed out and
 * the stamp grey or red as asked. Pure: no Android graphics.
 */
class PageCleanupConformanceTest {
    private class Box(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    ) {
        fun contains(
            px: Int,
            py: Int,
            grow: Int = 0,
        ) = px >= x - grow && px < x + w + grow && py >= y - grow && py < y + h + grow
    }

    private fun JsonObject.ints(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.int }

    private fun luma(
        r: Raster,
        x: Int,
        y: Int,
    ) = (299 * r.r(x, y) + 587 * r.g(x, y) + 114 * r.b(x, y) + 500) / 1000

    @Test
    fun everyPageCleanupCaseHolds() {
        val cases = Cases.section("page_cleanup_cases")
        assertTrue(cases.size >= 2)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val w = c.getValue("width").jsonPrimitive.int
            val h = c.getValue("height").jsonPrimitive.int
            val paper = c.ints("paper")
            val texts = c.getValue("text").jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.int } }
            val block = c.ints("block")
            val stampAt = c.getValue("stamp").jsonArray
            val (cx, cy, radius) = stampAt.take(3).map { it.jsonPrimitive.int }
            val stampColour = stampAt[3].jsonArray.map { it.jsonPrimitive.int }
            val textBoxes = texts.map { Box(it[0], it[1], it[2], it[3]) }
            val blockBox = Box(block[0], block[1], block[2], block[3])
            fun inStamp(
                x: Int,
                y: Int,
                r: Int,
            ) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
            val source =
                Raster.of(w, h) { x, y ->
                    val text = texts.firstOrNull { Box(it[0], it[1], it[2], it[3]).contains(x, y) }
                    when {
                        inStamp(x, y, radius) -> (stampColour[0] shl 16) or (stampColour[1] shl 8) or stampColour[2]
                        blockBox.contains(x, y) -> grey(block[4])
                        text != null -> grey(text[4])
                        else -> grey(roundHalfUp(paper[0] + (paper[1] - paper[0]) * x / (w - 1.0)))
                    }
                }
            val out = PageCleanup.clean(source, c.getValue("grayscale").jsonPrimitive.boolean)
            val expect = c.getValue("expect").jsonObject

            var paperCount = 0
            var white = 0
            var textSum = 0L
            var textCount = 0
            var blockSum = 0L
            var blockCount = 0
            var redSum = 0L
            var greenSum = 0L
            var stampCount = 0
            var stampSpread = 0
            val inner = Box(blockBox.x + MARGIN, blockBox.y + MARGIN, blockBox.w - 2 * MARGIN, blockBox.h - 2 * MARGIN)
            val stampBox = Box(cx - radius, cy - radius, 2 * radius + 1, 2 * radius + 1)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val l = luma(out, x, y)
                    when {
                        textBoxes.any { it.contains(x, y) } -> {
                            textSum += l
                            textCount++
                        }

                        inner.contains(x, y) -> {
                            blockSum += l
                            blockCount++
                        }

                        inStamp(x, y, radius - MARGIN) -> {
                            redSum += out.r(x, y)
                            greenSum += out.g(x, y)
                            stampCount++
                            stampSpread = maxOf(stampSpread, maxOf(out.r(x, y), out.g(x, y), out.b(x, y)) - minOf(out.r(x, y), out.g(x, y), out.b(x, y)))
                        }

                        textBoxes.none { it.contains(x, y, PAPER_GAP) } && !blockBox.contains(x, y, PAPER_GAP) &&
                            !stampBox.contains(x, y, PAPER_GAP) -> {
                            paperCount++
                            if (l >= WHITE) white++
                        }
                    }
                }
            }
            val whiteFraction = white.toDouble() / paperCount
            assertTrue("$id: paper white $whiteFraction", whiteFraction >= expect.getValue("paper_white_min_fraction").jsonPrimitive.double)
            val textMean = textSum.toDouble() / textCount
            assertTrue("$id: text mean $textMean", textMean <= expect.getValue("text_max_mean").jsonPrimitive.double)
            val blockMean = blockSum.toDouble() / blockCount
            assertTrue("$id: block mean $blockMean", blockMean <= expect.getValue("block_max_mean").jsonPrimitive.double)
            assertTrue("$id: block mean $blockMean", blockMean >= expect.getValue("block_min_mean").jsonPrimitive.double)
            expect["stamp_gray"]?.let { if (it.jsonPrimitive.boolean) assertTrue("$id: stamp spread $stampSpread", stampSpread <= 2) }
            expect["stamp_red_min"]?.let {
                val red = (redSum - greenSum).toDouble() / stampCount
                assertTrue("$id: stamp red $red", red >= it.jsonPrimitive.double)
            }
        }
    }

    private fun grey(v: Int) = (v shl 16) or (v shl 8) or v

    private companion object {
        const val MARGIN = 4
        const val PAPER_GAP = 6
        const val WHITE = 250
    }
}
