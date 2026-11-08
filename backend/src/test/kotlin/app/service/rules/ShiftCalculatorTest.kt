package app.service.rules

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ShiftCalculatorTest {

    private val lisbon = ZoneId.of("Europe/Lisbon")
    private val night = LocalTime.of(20, 0) to LocalTime.of(8, 0)

    @Test
    fun nightShiftSpansTwoDates() {
        val s = shiftOn(LocalDate.of(2026, 6, 10), night.first, night.second, true, lisbon)

        assertEquals(LocalDate.of(2026, 6, 10), s.start.atZone(lisbon).toLocalDate())
        assertEquals(LocalDate.of(2026, 6, 11), s.end.atZone(lisbon).toLocalDate())
        assertEquals(Duration.ofHours(12), Duration.between(s.start, s.end))
    }

    @Test
    fun nightShiftStartingTheDayBeforeDstStartsIsElevenHours() {
        val s = shiftOn(LocalDate.of(2026, 3, 28), night.first, night.second, true, lisbon)

        assertEquals(Duration.ofHours(11), Duration.between(s.start, s.end))
    }

    @Test
    fun nightShiftStartingTheDayBeforeDstEndsIsThirteenHours() {
        val s = shiftOn(LocalDate.of(2026, 10, 24), night.first, night.second, true, lisbon)

        assertEquals(Duration.ofHours(13), Duration.between(s.start, s.end))
    }

    @Test
    fun dayShiftEndsTheSameDate() {
        val s = shiftOn(LocalDate.of(2026, 6, 10), LocalTime.of(8, 0), LocalTime.of(20, 0), false, lisbon)

        assertEquals(LocalDate.of(2026, 6, 10), s.end.atZone(lisbon).toLocalDate())
        assertEquals(Duration.ofHours(12), Duration.between(s.start, s.end))
        assertEquals(false, s.isNight)
    }

    @Test
    fun equalStartAndEndIsTwentyFourHours() {
        val s = shiftOn(LocalDate.of(2026, 6, 10), LocalTime.of(8, 0), LocalTime.of(8, 0), false, lisbon)

        assertEquals(Duration.ofHours(24), Duration.between(s.start, s.end))
    }
}
