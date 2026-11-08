package app.service.availability

import app.model.AvailabilityBody
import app.model.CurrentUser
import app.model.MemberAvailability
import app.model.MyAvailabilityPeriod
import app.model.SubmissionRow
import app.persistence.availability.AvailabilityRepository
import app.service.team.TeamService
import java.util.UUID
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface AvailabilityResult {
    data object Ok : AvailabilityResult
    data object NotFound : AvailabilityResult
    data object Invalid : AvailabilityResult
    data object Conflict : AvailabilityResult
}

private val KINDS = setOf("AVAILABLE", "IF_NEEDED")

// Caps one request, so a bulk shortcut stays within a period's slots.
private const val MAX_MARKS = 500

// Reach: the member acting for self, or TeamService.canManage. "Self" always uses the member rules, even for a manager.
class AvailabilityService(private val repo: AvailabilityRepository, private val teams: TeamService) {

    fun myPeriods(actor: CurrentUser): List<MyAvailabilityPeriod> = repo.listMine(actor.id, actor.companyId)

    // Null when outside reach, unknown, or the target is not an active shift member of the team.
    fun memberCalendar(actor: CurrentUser, teamId: UUID, periodId: UUID, userId: UUID): MemberAvailability? {
        val self = actor.id == userId
        if (!self && !teams.canManage(actor, teamId)) return null
        val period = repo.period(teamId, actor.companyId, periodId) ?: return null
        // Members never see DRAFT periods.
        if (self && period.status == "DRAFT") return null
        val member = repo.shiftMember(teamId, actor.companyId, userId) ?: return null
        return MemberAvailability(
            userId, member.displayName, period, editable(self, period.status),
            repo.slots(periodId, teamId, actor.companyId, member.teamMemberId, userId),
        )
    }

    // Validation (400) runs before reach, then the locked transaction checks slots (404) before state (409).
    fun setMarks(actor: CurrentUser, teamId: UUID, periodId: UUID, userId: UUID, body: AvailabilityBody): AvailabilityResult {
        val marks = body.parse() ?: return AvailabilityResult.Invalid
        val self = actor.id == userId
        if (!self && !teams.canManage(actor, teamId)) return AvailabilityResult.NotFound
        return repo.lockForMarks(teamId, actor.companyId, periodId, userId) {
            when {
                self && status == "DRAFT" -> AvailabilityResult.NotFound
                !slotsInPeriod(marks.map { it.first }) -> AvailabilityResult.NotFound
                !editable(self, status) -> AvailabilityResult.Conflict
                else -> {
                    for ((slot, kind) in marks) if (kind == null) clear(slot) else set(slot, kind, actor.id)
                    if (!self) audit(actor.id, marksPayload(periodId, marks))
                    AvailabilityResult.Ok
                }
            }
        } ?: AvailabilityResult.NotFound
    }

    // Null when the caller cannot manage the team, or the period is not in it.
    fun submissions(actor: CurrentUser, teamId: UUID, periodId: UUID): List<SubmissionRow>? {
        if (!teams.canManage(actor, teamId)) return null
        repo.period(teamId, actor.companyId, periodId) ?: return null
        return repo.submissions(periodId, teamId, actor.companyId)
    }
}

// The member edits while OPEN; a manager also while CLOSED. Nothing once published.
private fun editable(self: Boolean, status: String): Boolean =
    if (self) status == "OPEN" else status == "OPEN" || status == "CLOSED"

// Null when invalid: empty or oversized, unknown kind, malformed or duplicate slotId.
private fun AvailabilityBody.parse(): List<Pair<UUID, String?>>? {
    if (marks.isEmpty() || marks.size > MAX_MARKS) return null
    val parsed = marks.map { m ->
        val id = runCatching { UUID.fromString(m.slotId) }.getOrNull() ?: return null
        if (m.kind != null && m.kind !in KINDS) return null
        id to m.kind
    }
    if (parsed.map { it.first }.distinct().size != parsed.size) return null
    return parsed
}

private fun marksPayload(periodId: UUID, marks: List<Pair<UUID, String?>>): String = buildJsonObject {
    put("periodId", periodId.toString())
    put("marks", buildJsonArray {
        marks.forEach { (slot, kind) -> add(buildJsonObject { put("slotId", slot.toString()); put("kind", kind) }) }
    })
}.toString()
