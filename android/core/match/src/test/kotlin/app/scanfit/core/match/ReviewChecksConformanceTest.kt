package app.scanfit.core.match

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewChecksConformanceTest {
    @Test
    fun everyReviewCheckCaseHolds() {
        val cases = Cases.section("review_check_cases")
        assertEquals("expected all eight shared cases", 8, cases.size)
        val results =
            cases.map { case ->
                val evaluation =
                    SlotEvaluation(
                        verdict = Verdict.valueOf(case.getValue("verdict").jsonPrimitive.content),
                        failed =
                        case.getValue("failed").jsonArray.map { failed ->
                            Constraint.entries.single { it.wire == failed.jsonPrimitive.content }
                        },
                    )
                val checks = ReviewChecks.of(evaluation)
                val actual = listOf(checks.size, checks.dimensions, checks.jpeg)
                assertEquals(
                    case.getValue("id").jsonPrimitive.content,
                    case.getValue("expect").jsonArray.map { it.jsonPrimitive.boolean },
                    actual,
                )
                actual
            }
        assertTrue(
            "cases must exercise both statuses for every chip",
            (0..2).all { index ->
                results.any { it[index] } && results.any { !it[index] }
            },
        )
    }
}
