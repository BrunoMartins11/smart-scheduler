package app.service.team

import app.persistence.team.TeamRepository
import app.service.user.CurrentUser
import java.util.UUID

sealed interface InviteResult {
    data class Created(val userId: UUID) : InviteResult
    data object NotFound : InviteResult
    data object EmailTaken : InviteResult
    data object Invalid : InviteResult
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
}
