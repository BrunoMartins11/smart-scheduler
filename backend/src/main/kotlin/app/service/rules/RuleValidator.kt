package app.service.rules

import app.model.TeamRules
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Shift(val start: Instant, val end: Instant, val isNight: Boolean)

enum class Rule { MIN_REST, MAX_CONSECUTIVE_WORK, MAX_AVG_WEEKLY, MIN_WEEKLY_REST, MAX_CONSECUTIVE_NIGHTS, REST_AFTER_NIGHT_RUN }

data class Violation(val rule: Rule, val shifts: List<Shift>)

// All rules are hard. Durations are Instant differences, so DST nights count at real length.
fun validate(rules: TeamRules, zone: ZoneId, shifts: List<Shift>): List<Violation> {
    val s = shifts.sortedBy { it.start }
    return buildList {
        addAll(minRest(rules, s))
        addAll(maxConsecutiveWork(rules, s))
        addAll(maxAvgWeekly(rules, s))
        addAll(minWeeklyRest(rules, s))
        rules.maxConsecutiveNights?.let { addAll(maxConsecutiveNights(it, zone, s)) }
        rules.restAfterNightRunMin?.let { addAll(restAfterNightRun(it, zone, s)) }
    }.distinct()
}

private val WEEK: Duration = Duration.ofDays(7)

// Checking consecutive pairs is enough: any overlap or short gap implies one between neighbours.
private fun minRest(rules: TeamRules, s: List<Shift>): List<Violation> {
    val min = Duration.ofMinutes(rules.minRestBetweenShiftsMin.toLong())
    return s.zipWithNext()
        .filter { (a, b) -> Duration.between(a.end, b.start) < min }
        .map { (a, b) -> Violation(Rule.MIN_REST, listOf(a, b)) }
}

// Back-to-back shifts (gap 0) chain; overlapping ones chain too (they also fail MIN_REST).
private fun maxConsecutiveWork(rules: TeamRules, s: List<Shift>): List<Violation> {
    val max = Duration.ofMinutes(rules.maxConsecutiveWorkMin.toLong())
    val chains = mutableListOf<MutableList<Shift>>()
    var chainEnd = Instant.MIN
    for (sh in s) {
        if (chains.isNotEmpty() && sh.start <= chainEnd) {
            chains.last().add(sh)
            if (sh.end > chainEnd) chainEnd = sh.end
        } else {
            chains.add(mutableListOf(sh))
            chainEnd = sh.end
        }
    }
    return chains
        .filter { Duration.between(it.first().start, it.maxOf { sh -> sh.end }) > max }
        .map { Violation(Rule.MAX_CONSECUTIVE_WORK, it) }
}

private fun maxAvgWeekly(rules: TeamRules, s: List<Shift>): List<Violation> {
    val window = WEEK.multipliedBy(rules.avgReferenceWeeks.toLong())
    // shortcut: divides by the full reference period even with shorter history, so new members are never flagged early; upgrade if the pilot wants strict ramp-up.
    val cap = Duration.ofMinutes(rules.maxAvgWeeklyWorkMin.toLong() * rules.avgReferenceWeeks)
    return s.mapNotNull { anchor ->
        val from = anchor.end.minus(window)
        val inWindow = s.filter { it.end > from && it.start < anchor.end }
        val worked = inWindow.fold(Duration.ZERO) { acc, sh ->
            acc.plus(Duration.between(maxOf(sh.start, from), minOf(sh.end, anchor.end)))
        }
        if (worked > cap) Violation(Rule.MAX_AVG_WEEKLY, inWindow) else null
    }
}

private fun minWeeklyRest(rules: TeamRules, s: List<Shift>): List<Violation> {
    val min = Duration.ofMinutes(rules.minWeeklyRestMin.toLong())
    return s.mapNotNull { anchor ->
        val to = anchor.start.plus(WEEK)
        val inWindow = s.filter { it.end > anchor.start && it.start < to }
        var cursor = anchor.start
        var longest = Duration.ZERO
        for (sh in inWindow) {
            val from = maxOf(sh.start, anchor.start)
            if (from > cursor) longest = maxOf(longest, Duration.between(cursor, from))
            val upTo = minOf(sh.end, to)
            if (upTo > cursor) cursor = upTo
        }
        if (to > cursor) longest = maxOf(longest, Duration.between(cursor, to))
        if (longest < min) Violation(Rule.MIN_WEEKLY_REST, inWindow) else null
    }
}

private fun maxConsecutiveNights(limit: Int, zone: ZoneId, s: List<Shift>): List<Violation> =
    nightRuns(zone, s)
        .filter { nightDates(it, zone).size > limit }
        .map { Violation(Rule.MAX_CONSECUTIVE_NIGHTS, it) }

private fun restAfterNightRun(minRest: Int, zone: ZoneId, s: List<Shift>): List<Violation> {
    val min = Duration.ofMinutes(minRest.toLong())
    return nightRuns(zone, s).mapNotNull { nights ->
        val last = nights.last()
        val next = s.getOrNull(s.indexOfFirst { it === last } + 1) ?: return@mapNotNull null
        if (Duration.between(last.end, next.start) < min) Violation(Rule.REST_AFTER_NIGHT_RUN, nights + next) else null
    }
}

// Night shifts grouped into runs of consecutive local dates; shifts on the same date share one date.
private fun nightRuns(zone: ZoneId, s: List<Shift>): List<List<Shift>> {
    val runs = mutableListOf<MutableList<Shift>>()
    var last: LocalDate? = null
    for (n in s.filter { it.isNight }) {
        val date = n.start.atZone(zone).toLocalDate()
        if (last == null || date > last.plusDays(1)) runs.add(mutableListOf(n)) else runs.last().add(n)
        last = date
    }
    return runs
}

private fun nightDates(nights: List<Shift>, zone: ZoneId): Set<LocalDate> =
    nights.map { it.start.atZone(zone).toLocalDate() }.toSet()
