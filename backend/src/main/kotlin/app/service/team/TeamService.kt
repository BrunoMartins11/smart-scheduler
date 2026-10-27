package app.service.team

import app.model.CurrentUser
import app.model.TeamRules
import app.persistence.team.TeamMemberRow
import app.persistence.team.TeamRepository
import app.persistence.team.TeamSummary
import java.util.UUID

sealed interface InviteResult {
    data class Created(val userId: UUID) : InviteResult
    data object NotFound : InviteResult
    data object EmailTaken : InviteResult
    data object Invalid : InviteResult
}

sealed interface MemberResult {
    data object Ok : MemberResult
    data object NotFound : MemberResult
    data object Invalid : MemberResult
}

class TeamService(private val teams: TeamRepository) {

    // Callers must answer 404, not 403, when false, so a team outside reach looks like a missing one.
    fun canManage(user: CurrentUser, teamId: UUID): Boolean =
        (user.isCompanyAdmin || teamId in user.managedTeamIds) && teams.teamInCompany(teamId, user.companyId)

    // Creates an unclaimed account (auth_subject NULL); resolveLogin claims it on first login by verified email.
    fun inviteMember(
        actor: CurrentUser,
        teamId: UUID,
        email: String,
        displayName: String,
        role: String,
        ftePercent: Int = 100,
        worksShifts: Boolean = false,
    ): InviteResult {
        val mail = email.trim()
        val name = displayName.trim()
        if (role !in setOf("MANAGER", "MEMBER") || ftePercent !in 1..100 || !mail.contains('@') || name.isEmpty()) {
            return InviteResult.Invalid
        }
        // 404, not 403, so a team outside reach looks like a missing one.
        if (!canManage(actor, teamId)) return InviteResult.NotFound
        if (role == "MANAGER" && !actor.isCompanyAdmin) return InviteResult.NotFound

        val userId = teams.createInvitedMember(actor.companyId, teamId, mail, name, role, ftePercent, worksShifts)
            ?: return InviteResult.EmailTaken
        return InviteResult.Created(userId)
    }

    // Only company admins create teams. Returns null when refused.
    fun createTeam(actor: CurrentUser, name: String): UUID? {
        val trimmed = name.trim()
        if (!actor.isCompanyAdmin || trimmed.isEmpty()) return null
        return teams.createTeam(actor.companyId, trimmed)
    }

    fun visibleTeams(actor: CurrentUser): List<TeamSummary> =
        teams.listVisibleTeams(actor.companyId, actor.id, actor.isCompanyAdmin)

    // Null when the caller cannot manage the team, so the route answers 404.
    fun listMembers(actor: CurrentUser, teamId: UUID): List<TeamMemberRow>? =
        if (canManage(actor, teamId)) teams.listMembers(teamId, actor.companyId) else null

    // Partial update: null fields are left unchanged. `active` covers reactivation (false is handled by removeMember).
    fun updateMember(
        actor: CurrentUser,
        teamId: UUID,
        userId: UUID,
        ftePercent: Int?,
        worksShifts: Boolean?,
        role: String?,
        active: Boolean? = null,
    ): MemberResult {
        if ((role != null && role !in setOf("MANAGER", "MEMBER")) || (ftePercent != null && ftePercent !in 1..100)) {
            return MemberResult.Invalid
        }
        if (!canManage(actor, teamId)) return MemberResult.NotFound
        // Same guard as removeMember: deactivating yourself would leave the team unmanaged by accident.
        if (active == false && userId == actor.id) return MemberResult.Invalid
        val current = teams.memberRole(teamId, actor.companyId, userId) ?: return MemberResult.NotFound
        // Same rule as invite: only a company admin creates or removes a MANAGER.
        val touchesManager = role != null && role != current && (role == "MANAGER" || current == "MANAGER")
        if (touchesManager && !actor.isCompanyAdmin) return MemberResult.NotFound
        // Toggling active on another MANAGER is admin-only, same as removeMember. Own row: self-deactivate is refused above, self-activate is a no-op.
        if (active != null && current == "MANAGER" && userId != actor.id && !actor.isCompanyAdmin) return MemberResult.NotFound
        // Unchanged role is passed as null so the column is never rewritten.
        val roleToWrite = role.takeIf { it != current }
        return if (teams.updateMember(teamId, actor.companyId, userId, ftePercent, worksShifts, roleToWrite, active)) {
            MemberResult.Ok
        } else {
            MemberResult.NotFound
        }
    }

    // Soft remove. Refuses self-removal so a team is never left unmanaged by accident.
    fun removeMember(actor: CurrentUser, teamId: UUID, userId: UUID): MemberResult {
        if (!canManage(actor, teamId)) return MemberResult.NotFound
        if (userId == actor.id) return MemberResult.Invalid
        val role = teams.memberRole(teamId, actor.companyId, userId) ?: return MemberResult.NotFound
        // Same rule as invite and role change: only a company admin removes a MANAGER.
        if (role == "MANAGER" && !actor.isCompanyAdmin) return MemberResult.NotFound
        return if (teams.deactivateMember(teamId, actor.companyId, userId)) MemberResult.Ok else MemberResult.NotFound
    }

    // Null when the caller cannot manage the team, so the route answers 404.
    fun getRules(actor: CurrentUser, teamId: UUID): TeamRules? =
        if (canManage(actor, teamId)) teams.getRules(teamId, actor.companyId) else null

    // Full replace. Validation runs before the reach check, same as updateMember.
    fun setRules(actor: CurrentUser, teamId: UUID, rules: TeamRules): MemberResult {
        val positive = listOf(
            rules.minRestBetweenShiftsMin, rules.maxConsecutiveWorkMin, rules.maxAvgWeeklyWorkMin, rules.minWeeklyRestMin,
            rules.maxConsecutiveNights, rules.restAfterNightRunMin,
        )
        if (positive.any { it != null && it <= 0 } || rules.avgReferenceWeeks !in 1..52) return MemberResult.Invalid
        if (!canManage(actor, teamId)) return MemberResult.NotFound
        return if (teams.updateRules(teamId, actor.companyId, rules)) MemberResult.Ok else MemberResult.NotFound
    }

    // Copies rules into toTeamId. The caller must manage both teams.
    fun copyRules(actor: CurrentUser, fromTeamId: UUID, toTeamId: UUID): MemberResult {
        if (!canManage(actor, fromTeamId) || !canManage(actor, toTeamId)) return MemberResult.NotFound
        return if (teams.copyRules(fromTeamId, toTeamId, actor.companyId)) MemberResult.Ok else MemberResult.NotFound
    }
}
