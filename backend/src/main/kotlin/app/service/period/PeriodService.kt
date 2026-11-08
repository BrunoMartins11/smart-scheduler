package app.service.period

import app.model.CurrentUser
import app.model.PERIOD_DATE
import app.model.PeriodBody
import app.model.PeriodSlot
import app.model.PeriodStatusBody
import app.model.SchedulePeriod
import app.model.SlotCoverageBody
import app.persistence.period.PeriodRepository
import app.service.team.TeamService
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface PeriodResult {
    data class Created(val id: UUID) : PeriodResult
    data object Ok : PeriodResult
    data object NotFound : PeriodResult
    data object Invalid : PeriodResult
    data object Conflict : PeriodResult
}

// Caps the slots one period can generate, so a typo in the dates cannot create thousands of rows.
private const val MAX_PERIOD_DAYS = 92L

private val STATUSES = setOf("DRAFT", "OPEN", "CLOSED", "PUBLISHED")

// Reach is TeamService.canManage: a team the caller cannot manage answers NotFound, never Forbidden.
class PeriodService(private val periods: PeriodRepository, private val teams: TeamService) {

    // Null when the caller cannot manage the team, so the route answers 404.
    fun list(actor: CurrentUser, teamId: UUID): List<SchedulePeriod>? =
        if (teams.canManage(actor, teamId)) periods.list(teamId, actor.companyId) else null

    // Validation runs before the reach check, same as ShiftTemplateService.
    fun create(actor: CurrentUser, teamId: UUID, body: PeriodBody): PeriodResult {
        val (start, end) = body.toRange() ?: return PeriodResult.Invalid
        if (!teams.canManage(actor, teamId)) return PeriodResult.NotFound
        val id = UUID.randomUUID()
        return when (periods.create(id, teamId, actor.companyId, start, end)) {
            PeriodRepository.CreateOutcome.CREATED -> PeriodResult.Created(id)
            PeriodRepository.CreateOutcome.NOT_FOUND -> PeriodResult.NotFound
            PeriodRepository.CreateOutcome.OVERLAP -> PeriodResult.Conflict
        }
    }

    // Null when the caller cannot manage the team or the period is not in it, so the route answers 404.
    fun slots(actor: CurrentUser, teamId: UUID, periodId: UUID): List<PeriodSlot>? =
        if (teams.canManage(actor, teamId)) periods.slots(periodId, teamId, actor.companyId) else null

    fun delete(actor: CurrentUser, teamId: UUID, periodId: UUID): PeriodResult {
        if (!teams.canManage(actor, teamId)) return PeriodResult.NotFound
        return periods.locked(periodId, teamId, actor.companyId) {
            if (status != "DRAFT") {
                PeriodResult.Conflict
            } else {
                deleteWithSlots()
                audit(actor.id, "DELETE", "{}")
                PeriodResult.Ok
            }
        } ?: PeriodResult.NotFound
    }

    fun setStatus(actor: CurrentUser, teamId: UUID, periodId: UUID, body: PeriodStatusBody): PeriodResult {
        if (body.status !in STATUSES) return PeriodResult.Invalid
        val deadline = body.inputDeadline?.let { parseInstant(it) ?: return PeriodResult.Invalid }
        if (!teams.canManage(actor, teamId)) return PeriodResult.NotFound
        return periods.locked(periodId, teamId, actor.companyId) {
            when {
                !allowed(status, body.status) -> PeriodResult.Conflict
                // Only DRAFT -> OPEN needs a deadline; a reopen keeps the stored one unless a new one is sent.
                deadline == null && status == "DRAFT" -> PeriodResult.Invalid
                else -> {
                    val effective = deadline ?: inputDeadline
                    updateStatus(body.status, effective)
                    val payload = buildJsonObject {
                        put("from", status)
                        put("to", body.status)
                        put("inputDeadline", effective?.toString())
                    }
                    audit(actor.id, actionFor(status), payload.toString())
                    PeriodResult.Ok
                }
            }
        } ?: PeriodResult.NotFound
    }

    fun patchSlot(actor: CurrentUser, teamId: UUID, periodId: UUID, slotId: UUID, body: SlotCoverageBody): PeriodResult {
        // Same bounds as shift templates: maxStaff is capped at the smallint range the column uses.
        if (body.minStaff < 0 || body.minStaff > body.maxStaff || body.maxStaff < 1 || body.maxStaff > Short.MAX_VALUE) {
            return PeriodResult.Invalid
        }
        if (!teams.canManage(actor, teamId)) return PeriodResult.NotFound
        return periods.locked(periodId, teamId, actor.companyId) {
            when {
                !hasSlot(slotId) -> PeriodResult.NotFound
                status == "PUBLISHED" -> PeriodResult.Conflict
                else -> {
                    setSlotStaff(slotId, body.minStaff, body.maxStaff)
                    PeriodResult.Ok
                }
            }
        } ?: PeriodResult.NotFound
    }
}

// The only moves allowed: DRAFT -> OPEN, OPEN -> CLOSED, CLOSED -> OPEN. PUBLISHED is never reached here.
private fun allowed(from: String, to: String): Boolean =
    (from == "DRAFT" && to == "OPEN") || (from == "OPEN" && to == "CLOSED") || (from == "CLOSED" && to == "OPEN")

private fun actionFor(from: String): String = when (from) {
    "DRAFT" -> "OPEN"
    "OPEN" -> "CLOSE"
    else -> "REOPEN"
}

// Null when the body is invalid: unparseable dates, end before start, or a range over MAX_PERIOD_DAYS.
private fun PeriodBody.toRange(): Pair<LocalDate, LocalDate>? {
    val start = runCatching { LocalDate.parse(startDate, PERIOD_DATE) }.getOrNull() ?: return null
    val end = runCatching { LocalDate.parse(endDate, PERIOD_DATE) }.getOrNull() ?: return null
    if (end < start || ChronoUnit.DAYS.between(start, end) + 1 > MAX_PERIOD_DAYS) return null
    return start to end
}

private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
