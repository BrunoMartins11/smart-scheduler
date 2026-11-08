package app.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.serialization.Serializable

// Request body. Marks stay strings until the service parses them; a null kind clears the mark.
@Serializable
data class AvailabilityBody(val marks: List<AvailabilityMark>)

@Serializable
data class AvailabilityMark(val slotId: String, val kind: String? = null)

data class ShiftMember(val teamMemberId: UUID, val displayName: String)

data class MyAvailabilityPeriod(
    val teamId: UUID,
    val teamName: String,
    val periodId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: String,
    val inputDeadline: Instant?,
    val submitted: Boolean,
)

data class MemberSlot(
    val id: UUID,
    val date: LocalDate,
    val templateName: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val isNight: Boolean,
    val kind: String?,
    val enteredByManager: Boolean,
)

data class MemberAvailability(
    val userId: UUID,
    val displayName: String,
    val period: SchedulePeriod,
    val editable: Boolean,
    val slots: List<MemberSlot>,
)

data class SubmissionRow(val userId: UUID, val displayName: String, val status: String)
