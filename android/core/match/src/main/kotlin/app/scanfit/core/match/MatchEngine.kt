package app.scanfit.core.match

import app.scanfit.core.inspect.ColorKind
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Issue
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.Exam
import app.scanfit.core.model.ExamStatus
import app.scanfit.core.model.FileFormat
import kotlin.math.abs

/**
 * "Which exams accept this file?" (ALGORITHMS section 4 / 9.7). Pure and deterministic: file facts + presets in,
 * verdicts out. The Checker, the review screen's match note and the fit engine's property tests all use it.
 */
object MatchEngine {
    private const val ASPECT_TOLERANCE = 0.03
    private const val PREFERRED_PIXEL_TOLERANCE = 2
    private const val NEAR_MISS_WINDOW_FRACTION = 0.5

    private val CONVERTIBLE = setOf(DetectedFormat.PNG, DetectedFormat.WEBP, DetectedFormat.HEIC)
    private val GRAYSCALE_KINDS = setOf(DocKind.SIGNATURE, DocKind.THUMB, DocKind.FINGERS, DocKind.DECLARATION)

    /** Slot types a file of this kind may be matched against (ALGORITHMS 4). */
    fun slotTypes(kind: DocKind): Set<DocType> = when (kind) {
        DocKind.PHOTO -> setOf(DocType.PHOTO, DocType.POSTCARD_PHOTO)
        DocKind.SIGNATURE -> setOf(DocType.SIGNATURE, DocType.TRIPLE_SIGNATURE)
        DocKind.THUMB -> setOf(DocType.LEFT_THUMB, DocType.THUMB_IMPRESSION)
        DocKind.DECLARATION -> setOf(DocType.HANDWRITTEN_DECLARATION)
        DocKind.FINGERS -> setOf(DocType.LEFT_HAND_FINGERS_THUMB, DocType.RIGHT_HAND_FINGERS_THUMB)
        DocKind.PDF_DOCUMENT -> emptySet() // any slot that accepts PDF, see [slotMatches]
    }

    private fun slotMatches(
        kind: DocKind,
        slot: DocSpec,
    ): Boolean = if (kind == DocKind.PDF_DOCUMENT) FileFormat.PDF in slot.formats else slot.type in slotTypes(kind)

    fun match(
        file: FileFacts,
        exams: List<Exam>,
        options: MatchOptions = MatchOptions(),
    ): MatchResult {
        val entries = ArrayList<MatchEntry>()
        for (exam in exams) {
            if (exam.status != ExamStatus.ACTIVE) continue
            for (slot in exam.documents) {
                if (!slotMatches(file.docKind, slot)) continue
                val e = evaluate(slot, file, options)
                if (e.verdict == Verdict.NO || e.verdict == Verdict.UNKNOWN) continue
                val unverified = exam.confidence == Confidence.LOW
                val verdict = if (unverified && e.verdict == Verdict.EXACT) Verdict.ACCEPTED else e.verdict
                entries +=
                    MatchEntry(
                        exam.id,
                        exam.name,
                        exam.body,
                        slot.type,
                        verdict,
                        unverified,
                        e.fix,
                        e.failed,
                        e.issues,
                        slot.sizeKb,
                    )
            }
        }
        val rank = options.popularity.withIndex().associate { it.value to it.index }
        val sorted =
            entries.sortedWith(
                compareBy<MatchEntry> { it.verdict.ordinal }
                    .thenBy { rank[it.examId] ?: Int.MAX_VALUE }
                    .thenBy { it.examName },
            )
        return MatchResult(sorted)
    }

    /** The verdict for one file against one slot (confidence is applied by [match], not here). */
    fun evaluate(
        slot: DocSpec,
        file: FileFacts,
        options: MatchOptions = MatchOptions(),
    ): SlotEvaluation {
        val size = slot.sizeKb
        if (size.min == null && size.max == null && slot.dimensions.mode == DimensionMode.NONE) {
            return SlotEvaluation(Verdict.UNKNOWN)
        }
        val failed =
            buildList {
                if (!formatOk(slot, file)) add(Constraint.FORMAT)
                if (!sizeOk(slot, file)) add(Constraint.SIZE_KB)
                if (!encodingOk(file, options)) add(Constraint.ENCODING)
                if (!dimsOk(slot, file)) add(Constraint.DIMS)
            }
        val issues = issuesFor(slot, file, failed)
        if (failed.isEmpty()) {
            val verdict = if (size.max != null && preferredDimsClose(slot, file)) Verdict.EXACT else Verdict.ACCEPTED
            return SlotEvaluation(verdict, issues = issues)
        }
        val fix = if (failed.size == 1) fixFor(failed.single(), slot, file) else null
        return SlotEvaluation(if (fix != null) Verdict.NEAR_MISS else Verdict.NO, failed, fix, issues)
    }

    private fun formatOk(
        slot: DocSpec,
        f: FileFacts,
    ): Boolean = when (f.format) {
        DetectedFormat.JPEG -> FileFormat.JPG in slot.formats || FileFormat.JPEG in slot.formats
        DetectedFormat.PNG -> FileFormat.PNG in slot.formats
        DetectedFormat.PDF -> FileFormat.PDF in slot.formats
        else -> false
    }

    private fun sizeOk(
        slot: DocSpec,
        f: FileFacts,
    ): Boolean {
        val min = slot.sizeKb.min ?: 0.0
        val max = slot.sizeKb.max
        return f.kb >= min && (max == null || f.kb <= max)
    }

    private fun encodingOk(
        f: FileFacts,
        options: MatchOptions,
    ): Boolean {
        if (f.format != DetectedFormat.JPEG) return true
        if (f.progressive) return false
        return when (f.color) {
            ColorKind.RGB -> true
            ColorKind.GRAY -> options.allowGrayscale && f.docKind in GRAYSCALE_KINDS
            else -> false
        }
    }

    private fun dimsOk(
        slot: DocSpec,
        f: FileFacts,
    ): Boolean {
        val w = f.width
        val h = f.height
        if (w == null || h == null || f.format == DetectedFormat.PDF) return true
        val d = slot.dimensions
        return when (d.mode) {
            DimensionMode.EXACT -> w == d.width && h == d.height
            DimensionMode.PREFERRED -> aspectClose(w, h, d.width, d.height)
            DimensionMode.RANGE -> insideBox(slot, w, h) && insideAspect(slot, w, h)
            DimensionMode.NONE -> true
        }
    }

    private fun aspectClose(
        w: Int,
        h: Int,
        pw: Int?,
        ph: Int?,
    ): Boolean {
        if (pw == null || ph == null || h == 0 || ph == 0) return false
        return abs((w.toDouble() / h) / (pw.toDouble() / ph) - 1.0) <= ASPECT_TOLERANCE
    }

    private fun insideBox(
        slot: DocSpec,
        w: Int,
        h: Int,
    ): Boolean {
        val d = slot.dimensions
        val widthOk = w >= (d.minW ?: 0) && w <= (d.maxW ?: Int.MAX_VALUE)
        val heightOk = h >= (d.minH ?: 0) && h <= (d.maxH ?: Int.MAX_VALUE)
        return widthOk && heightOk
    }

    private fun insideAspect(
        slot: DocSpec,
        w: Int,
        h: Int,
    ): Boolean {
        val range = slot.dimensions.aspectWOverH ?: return true
        if (h == 0) return false
        val a = w.toDouble() / h
        return a >= (range.min ?: 0.0) && a <= (range.max ?: Double.MAX_VALUE)
    }

    private fun preferredDimsClose(
        slot: DocSpec,
        f: FileFacts,
    ): Boolean {
        val d = slot.dimensions
        if (d.mode != DimensionMode.PREFERRED || f.width == null || f.height == null) return true
        val pw = d.width ?: return false
        val ph = d.height ?: return false
        return abs(f.width - pw) <= PREFERRED_PIXEL_TOLERANCE && abs(f.height - ph) <= PREFERRED_PIXEL_TOLERANCE
    }

    private fun fixFor(
        c: Constraint,
        slot: DocSpec,
        f: FileFacts,
    ): FixAction? = when (c) {
        Constraint.SIZE_KB -> {
            sizeFix(slot, f)
        }

        Constraint.FORMAT -> {
            if (f.format in CONVERTIBLE && acceptsJpeg(slot)) FixAction.CONVERT_TO_JPEG else null
        }

        Constraint.ENCODING -> {
            FixAction.REENCODE_BASELINE
        }

        Constraint.DIMS -> {
            null
        } // a dims failure needs a new crop, never a one-tap fix
    }

    private fun sizeFix(
        slot: DocSpec,
        f: FileFacts,
    ): FixAction? {
        val min = slot.sizeKb.min ?: 0.0
        val max = slot.sizeKb.max ?: return null
        val limit = (max - min) * NEAR_MISS_WINDOW_FRACTION
        return when {
            f.kb > max && f.kb - max <= limit -> FixAction.COMPRESS_TO_TARGET
            f.kb < min && min - f.kb <= limit -> FixAction.ENLARGE_TO_TARGET
            else -> null
        }
    }

    private fun acceptsJpeg(slot: DocSpec) = FileFormat.JPG in slot.formats || FileFormat.JPEG in slot.formats

    private fun sizeIssue(
        slot: DocSpec,
        f: FileFacts,
    ) = if (f.kb < (slot.sizeKb.min ?: 0.0)) Issue.TOO_SMALL_KB else Issue.TOO_LARGE_KB

    private fun dimsIssue(
        slot: DocSpec,
        f: FileFacts,
    ): Issue {
        val mode = slot.dimensions.mode
        val boxFails = mode == DimensionMode.RANGE && !insideBox(slot, f.width ?: 0, f.height ?: 0)
        return if (mode == DimensionMode.EXACT || boxFails) Issue.WRONG_DIMENSIONS else Issue.WRONG_ASPECT
    }

    private fun issueFor(
        c: Constraint,
        slot: DocSpec,
        f: FileFacts,
    ): Issue? = when (c) {
        Constraint.SIZE_KB -> sizeIssue(slot, f)
        Constraint.DIMS -> dimsIssue(slot, f)
        Constraint.ENCODING -> encodingIssue(f)
        Constraint.FORMAT -> null // no issue code here: HEIC/extension problems come from the inspector
    }

    private fun encodingIssue(f: FileFacts) = when {
        f.progressive -> Issue.PROGRESSIVE_JPEG
        f.color == ColorKind.CMYK -> Issue.CMYK_COLOR
        else -> Issue.GRAYSCALE_NOT_ALLOWED
    }

    private fun issuesFor(
        slot: DocSpec,
        f: FileFacts,
        failed: List<Constraint>,
    ): List<Issue> = buildList {
        failed.mapNotNullTo(this) { issueFor(it, slot, f) }
        val slotDpi = slot.dpi
        if (slotDpi != null && f.dpi != null && f.dpi != slotDpi) add(Issue.LOW_DPI_METADATA)
    }
}
