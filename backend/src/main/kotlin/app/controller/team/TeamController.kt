package app.controller.team

import app.controller.currentUser
import app.service.auth.AuthService
import app.service.team.InviteResult
import app.service.team.TeamService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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

fun Route.teamRoutes(auth: AuthService, teams: TeamService) {
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

    post("/api/teams/{teamId}/invites") {
        val user = call.currentUser(auth) ?: return@post
        val teamId = call.parameters["teamId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.NotFound)
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
