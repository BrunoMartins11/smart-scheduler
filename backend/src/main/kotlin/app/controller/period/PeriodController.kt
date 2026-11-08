package app.controller.period

import app.controller.bodyOrNull
import app.controller.currentUser
import app.controller.uuidParam
import app.model.PeriodBody
import app.model.PeriodSlot
import app.model.PeriodStatusBody
import app.model.SchedulePeriod
import app.model.SlotCoverageBody
import app.model.TIME
import app.service.auth.AuthService
import app.service.period.PeriodResult
import app.service.period.PeriodService
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun SchedulePeriod.toJson() = buildJsonObject {
    put("id", id.toString())
    put("startDate", startDate.toString())
    put("endDate", endDate.toString())
    put("status", status)
    put("inputDeadline", inputDeadline?.toString())
}

private fun PeriodSlot.toJson() = buildJsonObject {
    put("id", id.toString())
    put("date", date.toString())
    put("templateId", templateId.toString())
    put("templateName", templateName)
    put("startTime", startTime.format(TIME))
    put("endTime", endTime.format(TIME))
    put("isNight", isNight)
    put("minStaff", minStaff)
    put("maxStaff", maxStaff)
    put("defaultMinStaff", defaultMinStaff)
    put("defaultMaxStaff", defaultMaxStaff)
}

private suspend fun ApplicationCall.respondPeriod(result: PeriodResult, status: HttpStatusCode = HttpStatusCode.NoContent) =
    when (result) {
        is PeriodResult.Created -> {
            val json = buildJsonObject { put("id", result.id.toString()) }
            respondText(json.toString(), ContentType.Application.Json, status)
        }
        PeriodResult.Ok -> respond(HttpStatusCode.NoContent)
        PeriodResult.NotFound -> respond(HttpStatusCode.NotFound)
        PeriodResult.Invalid -> respond(HttpStatusCode.BadRequest)
        PeriodResult.Conflict -> respond(HttpStatusCode.Conflict)
    }

fun Route.periodRoutes(auth: AuthService, periods: PeriodService) {
    get("/api/teams/{teamId}/periods") {
        val user = call.currentUser(auth) ?: return@get
        val teamId = call.uuidParam("teamId") ?: return@get call.respond(HttpStatusCode.NotFound)
        val list = periods.list(user, teamId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val json = buildJsonArray { list.forEach { add(it.toJson()) } }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    post("/api/teams/{teamId}/periods") {
        val user = call.currentUser(auth) ?: return@post
        val teamId = call.uuidParam("teamId") ?: return@post call.respond(HttpStatusCode.NotFound)
        val body = call.bodyOrNull<PeriodBody>() ?: return@post call.respond(HttpStatusCode.BadRequest)
        call.respondPeriod(periods.create(user, teamId, body), HttpStatusCode.Created)
    }

    delete("/api/teams/{teamId}/periods/{periodId}") {
        val user = call.currentUser(auth) ?: return@delete
        val teamId = call.uuidParam("teamId")
        val periodId = call.uuidParam("periodId")
        if (teamId == null || periodId == null) return@delete call.respond(HttpStatusCode.NotFound)
        call.respondPeriod(periods.delete(user, teamId, periodId))
    }

    post("/api/teams/{teamId}/periods/{periodId}/status") {
        val user = call.currentUser(auth) ?: return@post
        val teamId = call.uuidParam("teamId")
        val periodId = call.uuidParam("periodId")
        if (teamId == null || periodId == null) return@post call.respond(HttpStatusCode.NotFound)
        val body = call.bodyOrNull<PeriodStatusBody>() ?: return@post call.respond(HttpStatusCode.BadRequest)
        call.respondPeriod(periods.setStatus(user, teamId, periodId, body))
    }

    get("/api/teams/{teamId}/periods/{periodId}/slots") {
        val user = call.currentUser(auth) ?: return@get
        val teamId = call.uuidParam("teamId")
        val periodId = call.uuidParam("periodId")
        if (teamId == null || periodId == null) return@get call.respond(HttpStatusCode.NotFound)
        val list = periods.slots(user, teamId, periodId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val json = buildJsonArray { list.forEach { add(it.toJson()) } }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    patch("/api/teams/{teamId}/periods/{periodId}/slots/{slotId}") {
        val user = call.currentUser(auth) ?: return@patch
        val teamId = call.uuidParam("teamId")
        val periodId = call.uuidParam("periodId")
        val slotId = call.uuidParam("slotId")
        if (teamId == null || periodId == null || slotId == null) return@patch call.respond(HttpStatusCode.NotFound)
        val body = call.bodyOrNull<SlotCoverageBody>() ?: return@patch call.respond(HttpStatusCode.BadRequest)
        call.respondPeriod(periods.patchSlot(user, teamId, periodId, slotId, body))
    }
}
