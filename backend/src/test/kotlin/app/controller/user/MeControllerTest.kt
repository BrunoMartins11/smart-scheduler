package app.controller.user

import app.addMember
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.routeTest
import app.sessionCookie
import app.uniq
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MeControllerTest {

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
}
