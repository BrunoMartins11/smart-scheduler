package app.service.rules

import app.model.TeamRules
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleValidatorTest {

    private val lisbon = ZoneId.of("Europe/Lisbon")
    private val pilot = TeamRules(720, 720, 2880, 26, 1440, null, null)
    // Lenient on every rule, so a test only fires the rule it sets.
    private val loose = TeamRules(1, 100_000, 100_000, 1, 1)

    private fun at(local: String) = LocalDateTime.parse(local).atZone(lisbon).toInstant()
    private fun sh(from: String, to: String, night: Boolean = false) = Shift(at(from), at(to), night)
    private fun fires(rule: Rule, rules: TeamRules, shifts: List<Shift>, zone: ZoneId = lisbon) =
        validate(rules, zone, shifts).any { it.rule == rule }
    private fun twelveHourDays(count: Int) = (0 until count).map { i ->
        val day = LocalDate.of(2026, 5, 4).plusDays(i.toLong())
        sh("${day}T08:00", "${day}T20:00")
    }

    // MIN_REST

    @Test
    fun minRestExactlyAtMinimumIsOk() {
        val rules = loose.copy(minRestBetweenShiftsMin = 720)
        val shifts = listOf(sh("2026-05-04T08:00", "2026-05-04T16:00"), sh("2026-05-05T04:00", "2026-05-05T12:00"))
        assertTrue(validate(rules, lisbon, shifts).isEmpty())
    }

    @Test
    fun minRestOneMinuteShortFires() {
        val rules = loose.copy(minRestBetweenShiftsMin = 720)
        val shifts = listOf(sh("2026-05-04T08:00", "2026-05-04T16:00"), sh("2026-05-05T03:59", "2026-05-05T12:00"))
        assertTrue(fires(Rule.MIN_REST, rules, shifts))
    }

    @Test
    fun overlappingShiftsFireEvenWhenGivenUnsorted() {
        val rules = loose.copy(minRestBetweenShiftsMin = 720)
        val a = sh("2026-05-04T08:00", "2026-05-04T16:00")
        val b = sh("2026-05-04T14:00", "2026-05-04T20:00")
        assertTrue(fires(Rule.MIN_REST, rules, listOf(b, a)))
    }

    // MAX_CONSECUTIVE_WORK

    @Test
    fun backToBackChainAtLimitIsOk() {
        val rules = loose.copy(maxConsecutiveWorkMin = 720)
        val shifts = listOf(sh("2026-05-04T08:00", "2026-05-04T14:00"), sh("2026-05-04T14:00", "2026-05-04T20:00"))
        assertFalse(fires(Rule.MAX_CONSECUTIVE_WORK, rules, shifts))
    }

    @Test
    fun backToBackChainOverLimitFires() {
        val rules = loose.copy(maxConsecutiveWorkMin = 720)
        val shifts = listOf(sh("2026-05-04T08:00", "2026-05-04T14:00"), sh("2026-05-04T14:00", "2026-05-04T21:00"))
        assertTrue(fires(Rule.MAX_CONSECUTIVE_WORK, rules, shifts))
    }

    @Test
    fun separatedShiftsDoNotChain() {
        val rules = loose.copy(maxConsecutiveWorkMin = 720)
        val shifts = listOf(sh("2026-05-04T08:00", "2026-05-04T20:00"), sh("2026-05-05T08:00", "2026-05-05T20:00"))
        assertFalse(fires(Rule.MAX_CONSECUTIVE_WORK, rules, shifts))
    }

    // MAX_AVG_WEEKLY: 2 reference weeks, cap 2880 min/week = 96h in the window

    @Test
    fun avgWeeklyAtLimitIsOk() {
        val rules = loose.copy(avgReferenceWeeks = 2, maxAvgWeeklyWorkMin = 2880)
        assertFalse(fires(Rule.MAX_AVG_WEEKLY, rules, twelveHourDays(8)))
    }

    @Test
    fun avgWeeklyOverLimitFires() {
        val rules = loose.copy(avgReferenceWeeks = 2, maxAvgWeeklyWorkMin = 2880)
        assertTrue(fires(Rule.MAX_AVG_WEEKLY, rules, twelveHourDays(9)))
    }

    // MIN_WEEKLY_REST: needs one gap of 24h in every 7 days

    @Test
    fun weeklyRestOkWhenAGapOfADayExists() {
        val rules = loose.copy(minWeeklyRestMin = 1440)
        val shifts = listOf(
            sh("2026-05-04T08:00", "2026-05-04T20:00"),
            sh("2026-05-06T08:00", "2026-05-06T20:00"),
            sh("2026-05-08T08:00", "2026-05-08T20:00"),
        )
        assertFalse(fires(Rule.MIN_WEEKLY_REST, rules, shifts))
    }

    @Test
    fun weeklyRestViolatedByContinuousWork() {
        val rules = loose.copy(minWeeklyRestMin = 1440)
        assertTrue(fires(Rule.MIN_WEEKLY_REST, rules, twelveHourDays(7)))
    }

    // MAX_CONSECUTIVE_NIGHTS

    private fun night(day: String) = sh("${day}T20:00", "${LocalDate.parse(day).plusDays(1)}T08:00", night = true)

    @Test
    fun nightRunAtLimitIsOk() {
        val rules = loose.copy(maxConsecutiveNights = 2)
        assertFalse(fires(Rule.MAX_CONSECUTIVE_NIGHTS, rules, listOf(night("2026-05-04"), night("2026-05-05"))))
    }

    @Test
    fun nightRunOverLimitFires() {
        val rules = loose.copy(maxConsecutiveNights = 2)
        val nights = listOf(night("2026-05-04"), night("2026-05-05"), night("2026-05-06"))
        assertTrue(fires(Rule.MAX_CONSECUTIVE_NIGHTS, rules, nights))
    }

    @Test
    fun sameDateNightsCountAsOneDate() {
        val rules = loose.copy(maxConsecutiveNights = 2)
        val shifts = listOf(
            sh("2026-05-04T00:00", "2026-05-04T06:00", night = true),
            night("2026-05-04"),
            night("2026-05-05"),
        )
        assertFalse(fires(Rule.MAX_CONSECUTIVE_NIGHTS, rules, shifts))
    }

    @Test
    fun nullNightLimitIsSkipped() {
        val nights = listOf(night("2026-05-04"), night("2026-05-05"), night("2026-05-06"), night("2026-05-07"))
        assertFalse(fires(Rule.MAX_CONSECUTIVE_NIGHTS, loose, nights))
    }

    // REST_AFTER_NIGHT_RUN: 24h after the last night of a run

    @Test
    fun restAfterNightRunExactlyMinimumIsOk() {
        val rules = loose.copy(restAfterNightRunMin = 1440)
        val shifts = listOf(night("2026-05-04"), night("2026-05-05"), sh("2026-05-07T08:00", "2026-05-07T16:00"))
        assertFalse(fires(Rule.REST_AFTER_NIGHT_RUN, rules, shifts))
    }

    @Test
    fun restAfterNightRunShortFires() {
        val rules = loose.copy(restAfterNightRunMin = 1440)
        val shifts = listOf(night("2026-05-04"), night("2026-05-05"), sh("2026-05-07T06:00", "2026-05-07T14:00"))
        assertTrue(fires(Rule.REST_AFTER_NIGHT_RUN, rules, shifts))
    }

    @Test
    fun nullRestAfterNightRunIsSkipped() {
        val shifts = listOf(night("2026-05-04"), night("2026-05-05"), sh("2026-05-07T06:00", "2026-05-07T14:00"))
        assertFalse(fires(Rule.REST_AFTER_NIGHT_RUN, loose, shifts))
    }

    // DST: Lisbon 2026-03-29 (spring, 23h day) and 2026-10-25 (autumn, 25h day)

    @Test
    fun dstNightsCountAtRealLength() {
        val spring = sh("2026-03-28T20:00", "2026-03-29T08:00", night = true)
        val autumn = sh("2026-10-24T20:00", "2026-10-25T08:00", night = true)
        assertEquals(Duration.ofHours(11), Duration.between(spring.start, spring.end))
        assertEquals(Duration.ofHours(13), Duration.between(autumn.start, autumn.end))

        val capped = loose.copy(maxConsecutiveWorkMin = 720)
        assertFalse(fires(Rule.MAX_CONSECUTIVE_WORK, capped, listOf(spring)))
        val flagged = validate(capped, lisbon, listOf(spring, autumn)).filter { it.rule == Rule.MAX_CONSECUTIVE_WORK }
        assertEquals(listOf(listOf(autumn)), flagged.map { it.shifts })
    }

    // pilot defaults

    @Test
    fun pilotAcceptsRealisticRoster() {
        val days = (0 until 4).flatMap { week ->
            listOf(0L, 2L, 4L).map { LocalDate.of(2026, 5, 4).plusDays(7 * week + it) }
        }
        val roster = days.map { sh("${it}T08:00", "${it}T20:00") }
        assertTrue(validate(pilot, lisbon, roster).isEmpty())
    }

    @Test
    fun pilotRejectsTwentyFourHourShift() {
        val shift = sh("2026-05-04T08:00", "2026-05-05T08:00")
        assertTrue(fires(Rule.MAX_CONSECUTIVE_WORK, pilot, listOf(shift)))
    }
}
