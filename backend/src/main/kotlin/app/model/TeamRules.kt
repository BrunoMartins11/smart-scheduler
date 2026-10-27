package app.model

import kotlinx.serialization.Serializable

// Mirrors the rule columns on `team`. The two nullable rules are null when unset.
@Serializable
data class TeamRules(
    val minRestBetweenShiftsMin: Int,
    val maxConsecutiveWorkMin: Int,
    val maxAvgWeeklyWorkMin: Int,
    val avgReferenceWeeks: Int,
    val minWeeklyRestMin: Int,
    val maxConsecutiveNights: Int? = null,
    val restAfterNightRunMin: Int? = null,
)
