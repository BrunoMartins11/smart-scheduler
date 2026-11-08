package app.model

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID
import kotlinx.serialization.Serializable

// Wire format for template times: "HH:mm", in the team's zone. STRICT, so "24:00" is refused rather than read as midnight.
val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

// Request body. Times stay strings until the service parses them.
@Serializable
data class ShiftTemplateBody(
    val name: String,
    val startTime: String,
    val endTime: String,
    val isNight: Boolean,
    val minStaff: Int,
    val maxStaff: Int,
)

data class ShiftTemplate(
    val id: UUID,
    val name: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val isNight: Boolean,
    val minStaff: Int,
    val maxStaff: Int,
    val active: Boolean,
)
