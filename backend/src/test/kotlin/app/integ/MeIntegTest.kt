package app.integ

import app.addMember
import app.controller.auth.SESSION_TTL_SECONDS
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.routeTest
import app.sessionCookie
import app.testConfig
import app.uniq
import java.util.UUID
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import io.ktor.util.hex
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The session shape before issuedAt was added, sealed with the same transformer as the app.
@Serializable
data class OldSession(val userId: String)

private fun oldSessionCookie(userId: UUID): String {
    val config = testConfig()
    val encryptKey = hex(config.property("session.encryptKey").getString())
    val signKey = hex(config.property("session.signKey").getString())
    var cookie = ""
    testApplication {
        application {
            install(Sessions) {
                cookie<OldSession>("sid") { transform(SessionTransportTransformerEncrypt(encryptKey, signKey)) }
            }
            routing {
                get("/") {
                    call.sessions.set(OldSession(userId.toString()))
                    call.respondText("")
                }
            }
        }
        val setCookie = client.get("/").headers[HttpHeaders.SetCookie]!!
        cookie = "sid=" + setCookie.substringAfter("sid=").substringBefore(";")
    }
    return cookie
}

class MeIntegTest {

    @Test
    fun meWithoutSessionIsUnauthorized() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
    }

    @Test
    fun meReturnsCurrentUserFields() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val email = "me-${uniq()}@x.pt"
        val userId = insertAccount(email, companyId = company, admin = true)
        addMember(team, userId, "MANAGER")

        val response = client.get("/api/me") { header(HttpHeaders.Cookie, sessionCookie(userId)) }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("id", "email", "displayName", "companyId", "isCompanyAdmin", "managedTeamIds"), body.keys)
        assertEquals(userId.toString(), body["id"]!!.jsonPrimitive.content)
        assertEquals(email, body["email"]!!.jsonPrimitive.content)
        assertEquals("Test", body["displayName"]!!.jsonPrimitive.content)
        assertEquals(company.toString(), body["companyId"]!!.jsonPrimitive.content)
        assertEquals(true, body["isCompanyAdmin"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(team.toString()), body["managedTeamIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun deactivatedUserIsUnauthorized() = routeTest {
        val userId = insertAccount("gone-${uniq()}@x.pt", companyId = insertCompany(), deactivated = true)

        val response = client.get("/api/me") { header(HttpHeaders.Cookie, sessionCookie(userId)) }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun sessionOlderThanTtlIsUnauthorized() = routeTest {
        val userId = insertAccount("old-${uniq()}@x.pt", companyId = insertCompany())
        val issuedAt = Instant.now().epochSecond - SESSION_TTL_SECONDS - 60

        val response = client.get("/api/me") { header(HttpHeaders.Cookie, sessionCookie(userId, issuedAt)) }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun sessionWithoutIssuedAtIsUnauthorized() = routeTest {
        val userId = insertAccount("legacy-${uniq()}@x.pt", companyId = insertCompany())

        val response = client.get("/api/me") { header(HttpHeaders.Cookie, oldSessionCookie(userId)) }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun meForUnknownAccountIsUnauthorized() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { header(HttpHeaders.Cookie, sessionCookie(UUID.randomUUID())) }.status)
    }

    @Test
    fun meListsOnlyActiveManagerTeams() = routeTest {
        val company = insertCompany()
        val managed = insertTeam(company)
        val memberOf = insertTeam(company)
        val inactive = insertTeam(company)
        val userId = insertAccount("user-${uniq()}@x.pt", companyId = company)
        addMember(managed, userId, "MANAGER")
        addMember(memberOf, userId, "MEMBER")
        addMember(inactive, userId, "MANAGER", active = false)

        val response = client.get("/api/me") { header(HttpHeaders.Cookie, sessionCookie(userId)) }

        val ids = Json.parseToJsonElement(response.bodyAsText()).jsonObject["managedTeamIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(managed.toString()), ids)
    }
}
