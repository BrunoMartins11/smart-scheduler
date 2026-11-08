package app.controller

import app.controller.auth.SESSION_TTL_SECONDS
import app.controller.auth.UserSession
import app.model.CurrentUser
import app.service.auth.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// Null when the path segment is not a UUID; the routes answer 404 for it.
fun ApplicationCall.uuidParam(name: String): UUID? =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

// Null when the body is not valid JSON for T; the routes answer 400 for it.
suspend inline fun <reified T> ApplicationCall.bodyOrNull(): T? = try { Json.decodeFromString<T>(receiveText()) } catch (e: SerializationException) { null }

// Shared by every protected route. Responds 401 and clears the session when there is no usable user.
suspend fun ApplicationCall.currentUser(auth: AuthService): CurrentUser? {
    val user = sessions.get<UserSession>()
        ?.takeIf { Instant.now().epochSecond - it.issuedAt < SESSION_TTL_SECONDS }
        ?.let { auth.currentUser(UUID.fromString(it.userId)) }
    if (user == null || user.deactivated) {
        sessions.clear<UserSession>()
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return user
}
