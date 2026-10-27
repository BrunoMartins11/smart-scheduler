package app.controller.team

import app.addMember
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.memberCount
import app.routeTest
import app.sessionCookie
import app.teamCompany
import app.uniq
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TeamControllerTest {

    private suspend fun ApplicationTestBuilder.postAs(userId: UUID, path: String, body: String): HttpResponse =
        client.post(path) {
            header(HttpHeaders.Cookie, sessionCookie(userId))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun inviteBody(email: String, role: String = "MEMBER") =
        """{"email":"$email","displayName":"Ana","role":"$role"}"""

    // POST /api/teams

    @Test
    fun adminCreatesTeamAndGets201() = routeTest {
        val company = insertCompany()
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)

        val response = postAs(admin, "/api/teams", """{"name":"Urgência"}""")

        assertEquals(HttpStatusCode.Created, response.status)
        val id = UUID.fromString(Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(company, teamCompany(id))
    }

    @Test
    fun nonAdminCreatesTeamGets404() = routeTest {
        val company = insertCompany()
        val member = insertAccount("member-${uniq()}@x.pt", companyId = company)

        assertEquals(HttpStatusCode.NotFound, postAs(member, "/api/teams", """{"name":"Blocos"}""").status)
    }

    @Test
    fun blankTeamNameGets400() = routeTest {
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.BadRequest, postAs(admin, "/api/teams", """{"name":"   "}""").status)
    }

    @Test
    fun malformedTeamJsonGets400() = routeTest {
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.BadRequest, postAs(admin, "/api/teams", "{not json").status)
    }

    // POST /api/teams/{teamId}/invites

    @Test
    fun managerInvitesMemberGets201() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val manager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, manager, "MANAGER")

        val response = postAs(manager, "/api/teams/$team/invites", inviteBody("new-${uniq()}@x.pt"))

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(2, memberCount(team))
    }

    @Test
    fun inviteToTeamOutsideReachGets404() = routeTest {
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)
        val otherTeam = insertTeam(insertCompany())

        val response = postAs(admin, "/api/teams/$otherTeam/invites", inviteBody("new-${uniq()}@x.pt"))

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(0, memberCount(otherTeam))
    }

    @Test
    fun inviteToMalformedTeamIdGets404() = routeTest {
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        val response = postAs(admin, "/api/teams/not-a-uuid/invites", inviteBody("new-${uniq()}@x.pt"))

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun duplicateInviteEmailGets409() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val email = "dup-${uniq()}@x.pt"
        insertAccount(email, companyId = company)

        val response = postAs(admin, "/api/teams/$team/invites", inviteBody(email.uppercase()))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(0, memberCount(team))
    }

    @Test
    fun inviteEmailUsedInOtherCompanyGets201() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val email = "shared-${uniq()}@x.pt"
        insertAccount(email, companyId = insertCompany())

        val response = postAs(admin, "/api/teams/$team/invites", inviteBody(email))

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(1, memberCount(team))
    }

    @Test
    fun invalidInviteRoleGets400() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)

        val response = postAs(admin, "/api/teams/$team/invites", inviteBody("new-${uniq()}@x.pt", role = "ADMIN"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, memberCount(team))
    }

    @Test
    fun malformedInviteBodyGets400() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)

        assertEquals(HttpStatusCode.BadRequest, postAs(admin, "/api/teams/$team/invites", "{not json").status)
        // Missing required displayName.
        assertEquals(
            HttpStatusCode.BadRequest,
            postAs(admin, "/api/teams/$team/invites", """{"email":"x@x.pt","role":"MEMBER"}""").status,
        )
    }
}
