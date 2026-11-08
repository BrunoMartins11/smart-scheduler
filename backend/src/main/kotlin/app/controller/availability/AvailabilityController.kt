package app.controller.availability

import app.controller.bodyOrNull
import app.controller.currentUser
import app.controller.period.toJson
import app.controller.uuidParam
import app.model.AvailabilityBody
import app.model.MemberAvailability
import app.model.MyAvailabilityPeriod
import app.model.SubmissionRow
import app.model.TIME
import app.service.auth.AuthService
import app.service.availability.AvailabilityResult
import app.service.availability.AvailabilityService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject

private fun MyAvailabilityPeriod.toJson() = buildJsonObject {
    put("teamId", teamId.toString())
    put("teamName", teamName)
    put("periodId", periodId.toString())
    put("startDate", startDate.toString())
    put("endDate", endDate.toString())
    put("status", status)
    put("inputDeadline", inputDeadline?.toString())
    put("submitted", submitted)
}

private fun MemberAvailability.toJson(): JsonObject = buildJsonObject {
    put("member", buildJsonObject { put("userId", userId.toString()); put("displayName", displayName) })
    put("period", period.toJson())
    put("editable", editable)
    put("slots", buildJsonArray {
        slots.forEach { s ->
            add(buildJsonObject {
                put("id", s.id.toString())
                put("date", s.date.toString())
                put("templateName", s.templateName)
                put("startTime", s.startTime.format(TIME))
                put("endTime", s.endTime.format(TIME))
                put("isNight", s.isNight)
                put("kind", s.kind)
                put("enteredByManager", s.enteredByManager)
            })
        }
    })
}

private fun SubmissionRow.toJson() = buildJsonObject {
    put("userId", userId.toString())
    put("displayName", displayName)
    put("status", status)
}

private suspend fun ApplicationCall.respondAvailability(result: AvailabilityResult) = when (result) {
    AvailabilityResult.Ok -> respond(HttpStatusCode.NoContent)
    AvailabilityResult.NotFound -> respond(HttpStatusCode.NotFound)
    AvailabilityResult.Invalid -> respond(HttpStatusCode.BadRequest)
    AvailabilityResult.Conflict -> respond(HttpStatusCode.Conflict)
}

fun Route.availabilityRoutes(auth: AuthService, availability: AvailabilityService) {
    get("/api/me/availability-periods") {
        val user = call.currentUser(auth) ?: return@get
        val json = buildJsonArray { availability.myPeriods(user).forEach { add(it.toJson()) } }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    get("/api/teams/{teamId}/periods/{periodId}/members/{userId}/availability") {
        val user = call.currentUser(auth) ?: return@get
        val teamId = call.uuidParam("teamId")
        val periodId = call.uuidParam("periodId")
        val userId = call.uuidParam("userId")
        if (teamId == null || periodId == null || userId == null) return@get call.respond(HttpStatusCode.NotFound)
        val view = availability.memberCalendar(user, teamId, periodId, userId) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondText(view.toJson().toString(), ContentType.Application.Json)
    }

    patch("/api/teams/{teamId}/periods/{periodId}/members/{userId}/availability") {
        val user = call.currentUser(auth) ?: return@patch
        val teamId = call.uuidParam("teamId")
        val periodId = call.uuidParam("periodId")
        val userId = call.uuidParam("userId")
        if (teamId == null || periodId == null || userId == null) return@patch call.respond(HttpStatusCode.NotFound)
        val body = call.bodyOrNull<AvailabilityBody>() ?: return@patch call.respond(HttpStatusCode.BadRequest)
        call.respondAvailability(availability.setMarks(user, teamId, periodId, userId, body))
    }

    get("/api/teams/{teamId}/periods/{periodId}/submissions") {
        val user = call.currentUser(auth) ?: return@get
        val teamId = call.uuidParam("teamId")
        val periodId = call.uuidParam("periodId")
        if (teamId == null || periodId == null) return@get call.respond(HttpStatusCode.NotFound)
        val rows = availability.submissions(user, teamId, periodId) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondText(buildJsonArray { rows.forEach { add(it.toJson()) } }.toString(), ContentType.Application.Json)
    }
}
