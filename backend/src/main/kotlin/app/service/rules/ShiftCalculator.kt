package app.service.rules

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// end <= start means the shift ends on the next date, so end == start is a 24 h shift.
// Built from ZonedDateTime, so DST changes give the real length.
fun shiftOn(date: LocalDate, startTime: LocalTime, endTime: LocalTime, isNight: Boolean, zone: ZoneId): Shift {
    val endDate = if (endTime <= startTime) date.plusDays(1) else date
    return Shift(
        start = date.atTime(startTime).atZone(zone).toInstant(),
        end = endDate.atTime(endTime).atZone(zone).toInstant(),
        isNight = isNight,
    )
}
