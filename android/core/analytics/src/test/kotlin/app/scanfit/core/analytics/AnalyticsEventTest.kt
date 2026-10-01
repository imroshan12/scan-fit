package app.scanfit.core.analytics

import app.scanfit.core.analytics.AnalyticsEvent.DocType
import app.scanfit.core.analytics.AnalyticsEvent.ExamId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalyticsEventTest {
    @Test
    fun namesAndParametersAreExactlyTheSpec() {
        val event =
            AnalyticsEvent.FitCompleted(
                DocType.SIGNATURE,
                AnalyticsEvent.FitStrategy.UPSCALE_THEN_PAD,
                AnalyticsEvent.MsBucket.LT_500,
                AnalyticsEvent.FitResult.OK,
            )
        assertEquals("fit_completed", event.name)
        assertEquals(
            mapOf(
                "doc_type" to "signature",
                "strategy" to "upscale_then_pad",
                "ms_bucket" to "lt_500",
                "result" to "ok",
            ),
            event.params.mapValues { (it.value as ParamValue.Text).value },
        )
    }

    @Test
    fun eventsWithoutParamsHaveNone() {
        assertTrue(AnalyticsEvent.FixTapped.params.isEmpty())
        assertEquals("rewarded_completed", AnalyticsEvent.RewardedCompleted.name)
    }

    @Test
    fun boundedIntsAreClampedSoABugCannotSendAHugeNumber() {
        val shown = AnalyticsEvent.MatchShown(AnalyticsEvent.CountBucket.N6_10, nFix = 5000)
        assertEquals(99L, (shown.params.getValue("n_fix") as ParamValue.Whole).value)
        assertEquals(0L, (AnalyticsEvent.CheckerRun(-3).params.getValue("issues_count") as ParamValue.Whole).value)
    }

    @Test
    fun examIdsAcceptPresetIdsAndRefusePathsUrisAndSpaces() {
        assertEquals("ibps_po", ExamId("ibps_po").value)
        for (bad in listOf("", "/Users/me/photo.jpg", "content://media/1", "Has Space", "a".repeat(41))) {
            try {
                ExamId(bad)
                fail("'$bad' must be refused")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun noParameterValueOfAnyEventCanContainAPathOrUri() {
        val events =
            listOf(
                AnalyticsEvent.ExamOpened(ExamId("neet_ug")),
                AnalyticsEvent.FlowStarted(DocType.PHOTO),
                AnalyticsEvent.ExportSaved(DocType.LEFT_THUMB, verified = true),
                AnalyticsEvent.PaywallShown(AnalyticsEvent.PaywallVariant.A, AnalyticsEvent.PaywallTrigger.FREE_LIMIT),
                AnalyticsEvent.Purchase(AnalyticsEvent.Product.PRO_YEARLY),
                AnalyticsEvent.PresetSync(AnalyticsEvent.SyncResult.UPDATED),
                AnalyticsEvent.PresetSyncFailed(AnalyticsEvent.SyncFailureReason.BAD_SIGNATURE),
                AnalyticsEvent.PortalFeedback(accepted = false),
                AnalyticsEvent.CoachAllGreen(AnalyticsEvent.SecondsBucket.LT_15),
            )
        for (event in events) {
            for (value in event.params.values.filterIsInstance<ParamValue.Text>()) {
                assertFalse(event.name, value.value.contains("/") || value.value.contains("content:"))
            }
        }
    }
}
