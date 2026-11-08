package app.service.shift

import app.model.CurrentUser
import app.model.ShiftTemplate
import app.model.ShiftTemplateBody
import app.model.TIME
import app.persistence.shift.ShiftTemplateRepository
import app.service.team.TeamService
import java.time.LocalTime
import java.util.UUID

sealed interface ShiftTemplateResult {
    data class Saved(val id: UUID) : ShiftTemplateResult
    data object Ok : ShiftTemplateResult
    data object NotFound : ShiftTemplateResult
    data object Invalid : ShiftTemplateResult
}

// Reach is TeamService.canManage: a team the caller cannot manage answers NotFound, never Forbidden.
class ShiftTemplateService(private val templates: ShiftTemplateRepository, private val teams: TeamService) {

    // Null when the caller cannot manage the team, so the route answers 404.
    fun list(actor: CurrentUser, teamId: UUID): List<ShiftTemplate>? =
        if (teams.canManage(actor, teamId)) templates.list(teamId, actor.companyId) else null

    // Validation runs before the reach check, same as setRules.
    fun create(actor: CurrentUser, teamId: UUID, body: ShiftTemplateBody): ShiftTemplateResult {
        val id = UUID.randomUUID()
        val t = body.toTemplate(id) ?: return ShiftTemplateResult.Invalid
        if (!teams.canManage(actor, teamId)) return ShiftTemplateResult.NotFound
        return if (templates.create(id, teamId, actor.companyId, t)) ShiftTemplateResult.Saved(id) else ShiftTemplateResult.NotFound
    }

    // A used template is never changed in place: the repository makes a new version and deactivates the old one.
    fun edit(actor: CurrentUser, teamId: UUID, templateId: UUID, body: ShiftTemplateBody): ShiftTemplateResult {
        val t = body.toTemplate(templateId) ?: return ShiftTemplateResult.Invalid
        if (!teams.canManage(actor, teamId)) return ShiftTemplateResult.NotFound
        val id = templates.edit(templateId, teamId, actor.companyId, t, UUID.randomUUID())
            ?: return ShiftTemplateResult.NotFound
        return ShiftTemplateResult.Saved(id)
    }

    fun setActive(actor: CurrentUser, teamId: UUID, templateId: UUID, active: Boolean): ShiftTemplateResult {
        if (!teams.canManage(actor, teamId)) return ShiftTemplateResult.NotFound
        return if (templates.setActive(templateId, teamId, actor.companyId, active)) ShiftTemplateResult.Ok
        else ShiftTemplateResult.NotFound
    }
}

// Null when the body is invalid. New templates start active; the repository keeps the flag on edit.
private fun ShiftTemplateBody.toTemplate(id: UUID): ShiftTemplate? {
    val start = runCatching { LocalTime.parse(startTime, TIME) }.getOrNull() ?: return null
    val end = runCatching { LocalTime.parse(endTime, TIME) }.getOrNull() ?: return null
    val trimmed = name.trim()
    // maxStaff is capped at the smallint range the column uses.
    if (trimmed.isEmpty() || minStaff < 0 || maxStaff < 1 || minStaff > maxStaff || maxStaff > Short.MAX_VALUE) return null
    return ShiftTemplate(id, trimmed, start, end, isNight, minStaff, maxStaff, active = true)
}
