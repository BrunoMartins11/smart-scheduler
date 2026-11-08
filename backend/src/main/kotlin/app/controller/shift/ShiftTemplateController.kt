package app.controller.shift

import app.controller.bodyOrNull
import app.controller.currentUser
import app.controller.uuidParam
import app.model.ShiftTemplate
import app.model.ShiftTemplateBody
import app.model.TIME
import app.service.auth.AuthService
import app.service.shift.ShiftTemplateResult
import app.service.shift.ShiftTemplateService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SetActiveRequest(val active: Boolean)

private fun ShiftTemplate.toJson() = buildJsonObject {
    put("id", id.toString())
    put("name", name)
    put("startTime", startTime.format(TIME))
    put("endTime", endTime.format(TIME))
    put("isNight", isNight)
    put("minStaff", minStaff)
    put("maxStaff", maxStaff)
    put("active", active)
}

private suspend fun ApplicationCall.respondTemplate(result: ShiftTemplateResult, status: HttpStatusCode = HttpStatusCode.OK) =
    when (result) {
        is ShiftTemplateResult.Saved -> {
            val json = buildJsonObject { put("id", result.id.toString()) }
            respondText(json.toString(), ContentType.Application.Json, status)
        }
        ShiftTemplateResult.Ok -> respond(HttpStatusCode.NoContent)
        ShiftTemplateResult.NotFound -> respond(HttpStatusCode.NotFound)
        ShiftTemplateResult.Invalid -> respond(HttpStatusCode.BadRequest)
    }

fun Route.shiftTemplateRoutes(auth: AuthService, shiftTemplates: ShiftTemplateService) {
    get("/api/teams/{teamId}/shift-templates") {
        val user = call.currentUser(auth) ?: return@get
        val teamId = call.uuidParam("teamId") ?: return@get call.respond(HttpStatusCode.NotFound)
        val list = shiftTemplates.list(user, teamId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val json = buildJsonArray { list.forEach { add(it.toJson()) } }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    post("/api/teams/{teamId}/shift-templates") {
        val user = call.currentUser(auth) ?: return@post
        val teamId = call.uuidParam("teamId") ?: return@post call.respond(HttpStatusCode.NotFound)
        val body = call.bodyOrNull<ShiftTemplateBody>() ?: return@post call.respond(HttpStatusCode.BadRequest)
        call.respondTemplate(shiftTemplates.create(user, teamId, body), HttpStatusCode.Created)
    }

    put("/api/teams/{teamId}/shift-templates/{templateId}") {
        val user = call.currentUser(auth) ?: return@put
        val teamId = call.uuidParam("teamId")
        val templateId = call.uuidParam("templateId")
        if (teamId == null || templateId == null) return@put call.respond(HttpStatusCode.NotFound)
        val body = call.bodyOrNull<ShiftTemplateBody>() ?: return@put call.respond(HttpStatusCode.BadRequest)
        call.respondTemplate(shiftTemplates.edit(user, teamId, templateId, body))
    }

    // Soft delete: the row stays, so existing slots keep their template.
    delete("/api/teams/{teamId}/shift-templates/{templateId}") {
        val user = call.currentUser(auth) ?: return@delete
        val teamId = call.uuidParam("teamId")
        val templateId = call.uuidParam("templateId")
        if (teamId == null || templateId == null) return@delete call.respond(HttpStatusCode.NotFound)
        call.respondTemplate(shiftTemplates.setActive(user, teamId, templateId, active = false))
    }

    patch("/api/teams/{teamId}/shift-templates/{templateId}") {
        val user = call.currentUser(auth) ?: return@patch
        val teamId = call.uuidParam("teamId")
        val templateId = call.uuidParam("templateId")
        if (teamId == null || templateId == null) return@patch call.respond(HttpStatusCode.NotFound)
        // Only { "active": bool } is accepted; any other body is 400.
        val body = call.bodyOrNull<SetActiveRequest>() ?: return@patch call.respond(HttpStatusCode.BadRequest)
        call.respondTemplate(shiftTemplates.setActive(user, teamId, templateId, body.active))
    }
}
