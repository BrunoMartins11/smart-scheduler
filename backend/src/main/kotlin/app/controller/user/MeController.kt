package app.controller.user

import app.controller.auth.currentUser
import app.service.user.UserService
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

fun Route.meRoutes(users: UserService) {
    get("/api/me") {
        val user = call.currentUser(users) ?: return@get
        val body = buildJsonObject {
            put("id", user.id.toString())
            put("email", user.email)
            put("displayName", user.displayName)
            put("companyId", user.companyId.toString())
            put("isCompanyAdmin", user.isCompanyAdmin)
            putJsonArray("managedTeamIds") { user.managedTeamIds.forEach { add(JsonPrimitive(it.toString())) } }
        }
        call.respondText(body.toString(), ContentType.Application.Json)
    }
}
