package app.scanfit.core.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.match.FixAction
import app.scanfit.core.match.MatchEntry
import app.scanfit.core.match.MatchNote
import app.scanfit.core.match.MatchResult
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocType
import app.scanfit.core.model.SizeKb

/** Sample notes for previews of the review screens (no presets are loaded in a preview). */
object MatchNoteSamples {
    private fun entry(
        id: String,
        name: String,
        body: String,
        verdict: Verdict,
        unverified: Boolean = false,
        fix: FixAction? = null,
    ) = MatchEntry(
        examId = id,
        examName = name,
        body = body,
        docType = DocType.SIGNATURE,
        verdict = verdict,
        unverified = unverified,
        fix = fix,
        failed = emptyList(),
        issues = emptyList(),
        sizeKb = IBPS_SIGNATURE,
    )

    /** The IBPS signature window, 10–20 KB. */
    private val IBPS_SIGNATURE = SizeKb(min = 10.0, max = 20.0)

    /** Five verified exams, one unverified, one near miss. */
    val accepted: MatchNote =
        MatchNote.of(
            MatchResult(
                listOf(
                    entry("ibps_po", "IBPS PO / MT", "IBPS", Verdict.EXACT),
                    entry("sbi_po", "SBI PO", "SBI", Verdict.EXACT),
                    entry("rbi_grade_b", "RBI Grade B (DR)", "RBI", Verdict.ACCEPTED),
                    entry("lic_aao", "LIC AAO", "LIC", Verdict.ACCEPTED),
                    entry("ibps_clerk", "IBPS Clerk (CSA)", "IBPS", Verdict.ACCEPTED),
                    entry("upsc_ese", "UPSC Engineering Services (ESE)", "UPSC", Verdict.ACCEPTED, unverified = true),
                    entry("ssc_cgl", "SSC CGL", "SSC", Verdict.NEAR_MISS, fix = FixAction.COMPRESS_TO_TARGET),
                ),
            ),
        )

    /** Only an unverified exam accepts the file. */
    val likelyOk: MatchNote =
        MatchNote.of(
            MatchResult(listOf(entry("upsc_ese", "UPSC Engineering Services (ESE)", "UPSC", Verdict.ACCEPTED, true))),
        )
}

@Preview(name = "Match note", showBackground = true)
@Preview(name = "Match note dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Match note large font", showBackground = true, fontScale = 2f)
@Preview(name = "Match note Hindi", showBackground = true, locale = "hi")
@Composable
private fun MatchNoteCardPreview() {
    ScanFitTheme {
        Column(Modifier.padding(ScanFitSpacing.lg), verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md)) {
            MatchNoteCard(MatchNoteSamples.accepted)
            MatchNoteCard(MatchNoteSamples.likelyOk)
            MatchNoteCard(MatchNote.EMPTY)
        }
    }
}
