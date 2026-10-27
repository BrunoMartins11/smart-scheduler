package app.controller.team

import app.controller.currentUser
import app.model.TeamRules
import app.service.auth.AuthService
import app.service.team.InviteResult
import app.service.team.MemberResult
import app.service.team.TeamService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class InviteRequest(
    val email: String,
    val displayName: String,
    val role: String,
    val ftePercent: Int = 100,
    val worksShifts: Boolean = false,
)

@Serializable
data class CreateTeamRequest(val name: String)

@Serializable
data class CopyRulesRequest(val fromTeamId: String)

@Serializable
data class UpdateMemberRequest(
    val ftePercent: Int? = null,
    val worksShifts: Boolean? = null,
    val role: String? = null,
    val active: Boolean? = null,
)

private fun ApplicationCall.uuidParam(name: String): UUID? =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private suspend fun ApplicationCall.respondMember(result: MemberResult) = when (result) {
    MemberResult.Ok -> respond(HttpStatusCode.NoContent)
    MemberResult.NotFound -> respond(HttpStatusCode.NotFound)
    MemberResult.Invalid -> respond(HttpStatusCode.BadRequest)
}

fun Route.teamRoutes(auth: AuthService, teams: TeamService) {
    get("/api/teams") {
        val user = call.currentUser(auth) ?: return@get
        val json = buildJsonArray {
            teams.visibleTeams(user).forEach { team ->
                add(buildJsonObject { put("id", team.id.toString()); put("name", team.name) })
            }
        }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    get("/api/teams/{teamId}/members") {
        val user = call.currentUser(auth) ?: return@get
        val teamId = call.uuidParam("teamId") ?: return@get call.respond(HttpStatusCode.NotFound)
        val members = teams.listMembers(user, teamId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val json = buildJsonArray {
            members.forEach { m ->
                add(buildJsonObject {
                    put("userId", m.userId.toString())
                    put("email", m.email)
                    put("displayName", m.displayName)
                    put("role", m.role)
                    put("ftePercent", m.ftePercent)
                    put("worksShifts", m.worksShifts)
                    put("active", m.active)
                })
            }
        }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    patch("/api/teams/{teamId}/members/{userId}") {
        val user = call.currentUser(auth) ?: return@patch
        val teamId = call.uuidParam("teamId")
        val userId = call.uuidParam("userId")
        if (teamId == null || userId == null) return@patch call.respond(HttpStatusCode.NotFound)
        val body = try {
            Json.decodeFromString<UpdateMemberRequest>(call.receiveText())
        } catch (e: SerializationException) {
            return@patch call.respond(HttpStatusCode.BadRequest)
        }
        call.respondMember(teams.updateMember(user, teamId, userId, body.ftePercent, body.worksShifts, body.role, body.active))
    }

    delete("/api/teams/{teamId}/members/{userId}") {
        val user = call.currentUser(auth) ?: return@delete
        val teamId = call.uuidParam("teamId")
        val userId = call.uuidParam("userId")
        if (teamId == null || userId == null) return@delete call.respond(HttpStatusCode.NotFound)
        call.respondMember(teams.removeMember(user, teamId, userId))
    }
    post("/api/teams") {
        val user = call.currentUser(auth) ?: return@post
        val body = try {
            Json.decodeFromString<CreateTeamRequest>(call.receiveText())
        } catch (e: SerializationException) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        if (body.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
        val id = teams.createTeam(user, body.name) ?: return@post call.respond(HttpStatusCode.NotFound)
        val json = buildJsonObject { put("id", id.toString()) }
        call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.Created)
    }

    get("/api/teams/{teamId}/rules") {
        val user = call.currentUser(auth) ?: return@get
        val teamId = call.uuidParam("teamId") ?: return@get call.respond(HttpStatusCode.NotFound)
        val rules = teams.getRules(user, teamId) ?: return@get call.respond(HttpStatusCode.NotFound)
        // Explicit build: Json omits null-default properties, but the API must send every field.
        val json = buildJsonObject {
            put("minRestBetweenShiftsMin", rules.minRestBetweenShiftsMin)
            put("maxConsecutiveWorkMin", rules.maxConsecutiveWorkMin)
            put("maxAvgWeeklyWorkMin", rules.maxAvgWeeklyWorkMin)
            put("avgReferenceWeeks", rules.avgReferenceWeeks)
            put("minWeeklyRestMin", rules.minWeeklyRestMin)
            put("maxConsecutiveNights", rules.maxConsecutiveNights)
            put("restAfterNightRunMin", rules.restAfterNightRunMin)
        }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    put("/api/teams/{teamId}/rules") {
        val user = call.currentUser(auth) ?: return@put
        val teamId = call.uuidParam("teamId") ?: return@put call.respond(HttpStatusCode.NotFound)
        val body = try {
            Json.decodeFromString<TeamRules>(call.receiveText())
        } catch (e: SerializationException) {
            return@put call.respond(HttpStatusCode.BadRequest)
        }
        call.respondMember(teams.setRules(user, teamId, body))
    }

    post("/api/teams/{teamId}/rules/copy") {
        val user = call.currentUser(auth) ?: return@post
        val teamId = call.uuidParam("teamId") ?: return@post call.respond(HttpStatusCode.NotFound)
        val body = try {
            Json.decodeFromString<CopyRulesRequest>(call.receiveText())
        } catch (e: SerializationException) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        val fromTeamId = runCatching { UUID.fromString(body.fromTeamId) }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest)
        call.respondMember(teams.copyRules(user, fromTeamId, teamId))
    }

    post("/api/teams/{teamId}/invites") {
        val user = call.currentUser(auth) ?: return@post
        val teamId = call.uuidParam("teamId") ?: return@post call.respond(HttpStatusCode.NotFound)
        val body = try {
            Json.decodeFromString<InviteRequest>(call.receiveText())
        } catch (e: SerializationException) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        when (val result = teams.inviteMember(
            user, teamId, body.email, body.displayName, body.role, body.ftePercent, body.worksShifts,
        )) {
            is InviteResult.Created -> {
                val json = buildJsonObject { put("userId", result.userId.toString()) }
                call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.Created)
            }
            InviteResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            InviteResult.EmailTaken -> call.respond(HttpStatusCode.Conflict)
            InviteResult.Invalid -> call.respond(HttpStatusCode.BadRequest)
        }
    }
}
