package app.controller

import app.controller.auth.UserSession
import app.model.CurrentUser
import app.service.auth.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import java.util.UUID

// Shared by every protected route. Responds 401 and clears the session when there is no usable user.
suspend fun ApplicationCall.currentUser(auth: AuthService): CurrentUser? {
    val user = sessions.get<UserSession>()?.let { auth.currentUser(UUID.fromString(it.userId)) }
    if (user == null || user.deactivated) {
        sessions.clear<UserSession>()
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return user
}
