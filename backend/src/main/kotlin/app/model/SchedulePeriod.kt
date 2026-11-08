package app.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID
import kotlinx.serialization.Serializable

// Wire format for period dates: "YYYY-MM-DD". STRICT, so "2026-02-30" is refused rather than clamped.
val PERIOD_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)

// Request bodies. Dates and instants stay strings until the service parses them.
@Serializable
data class PeriodBody(val startDate: String, val endDate: String)

@Serializable
data class PeriodStatusBody(val status: String, val inputDeadline: String? = null)

@Serializable
data class SlotCoverageBody(val minStaff: Int, val maxStaff: Int)

data class SchedulePeriod(
    val id: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: String,
    val inputDeadline: Instant?,
)

data class PeriodSlot(
    val id: UUID,
    val date: LocalDate,
    val templateId: UUID,
    val templateName: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val isNight: Boolean,
    val minStaff: Int,
    val maxStaff: Int,
    // The template version's defaults the slot was generated from, not the current row.
    val defaultMinStaff: Int,
    val defaultMaxStaff: Int,
)
