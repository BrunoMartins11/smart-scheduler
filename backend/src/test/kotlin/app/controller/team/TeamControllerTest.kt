package app.controller.team

import app.MemberState
import app.addMember
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.memberCount
import app.memberState
import app.routeTest
import app.sessionCookie
import app.teamCompany
import app.uniq
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
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

    // GET /api/teams

    private suspend fun ApplicationTestBuilder.getAs(userId: UUID, path: String): HttpResponse =
        client.get(path) { header(HttpHeaders.Cookie, sessionCookie(userId)) }

    private suspend fun ApplicationTestBuilder.patchAs(userId: UUID, path: String, body: String): HttpResponse =
        client.patch(path) {
            header(HttpHeaders.Cookie, sessionCookie(userId))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.deleteAs(userId: UUID, path: String): HttpResponse =
        client.delete(path) { header(HttpHeaders.Cookie, sessionCookie(userId)) }

    private suspend fun HttpResponse.jsonArray(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray

    private fun JsonArray.ids(): Set<String> = mapTo(mutableSetOf()) { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun adminListsAllCompanyTeamsMemberListsOwnActiveOnly() = routeTest {
        val company = insertCompany()
        val mine = insertTeam(company)
        val inactive = insertTeam(company)
        val foreign = insertTeam(insertCompany())
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(mine, memberId, "MEMBER")
        addMember(inactive, memberId, "MEMBER", active = false)

        val adminIds = getAs(admin, "/api/teams").jsonArray().ids()
        assertTrue(mine.toString() in adminIds && inactive.toString() in adminIds)
        assertFalse(foreign.toString() in adminIds)

        val response = getAs(memberId, "/api/teams")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(setOf(mine.toString()), response.jsonArray().ids())
    }

    // GET /api/teams/{teamId}/members

    @Test
    fun membersListedForAdminAndManagerOfTeamIncludingInactive() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        addMember(team, insertAccount("gone-${uniq()}@x.pt", companyId = company), "MEMBER", active = false)

        for (viewer in listOf(admin, managerId)) {
            val response = getAs(viewer, "/api/teams/$team/members")
            assertEquals(HttpStatusCode.OK, response.status)
            val members = response.jsonArray()
            assertEquals(2, members.size)
            val first = members[0].jsonObject
            assertEquals(setOf("userId", "email", "displayName", "role", "ftePercent", "worksShifts", "active"), first.keys)
        }
    }

    @Test
    fun membersHiddenWithNotFoundOutsideReach() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val otherTeam = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(otherTeam, managerId, "MANAGER")
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.NotFound, getAs(managerId, "/api/teams/$team/members").status)
        assertEquals(HttpStatusCode.NotFound, getAs(memberId, "/api/teams/$team/members").status)
        assertEquals(HttpStatusCode.NotFound, getAs(foreignAdmin, "/api/teams/$team/members").status)
        assertEquals(HttpStatusCode.NotFound, getAs(memberId, "/api/teams/not-a-uuid/members").status)
    }

    // PATCH /api/teams/{teamId}/members/{userId}

    @Test
    fun managerPatchesOnlyProvidedFields() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")

        val response = patchAs(managerId, "/api/teams/$team/members/$memberId", """{"ftePercent":60}""")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(MemberState("MEMBER", 60, false, true), memberState(team, memberId))
    }

    @Test
    fun patchOutsideReachOrUnknownGets404() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val otherTeam = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(otherTeam, memberId, "MEMBER")

        assertEquals(HttpStatusCode.NotFound, patchAs(managerId, "/api/teams/$otherTeam/members/$memberId", """{"ftePercent":60}""").status)
        assertEquals(HttpStatusCode.NotFound, patchAs(managerId, "/api/teams/$team/members/${UUID.randomUUID()}", """{"ftePercent":60}""").status)
        assertEquals(100, memberState(otherTeam, memberId).ftePercent)
    }

    @Test
    fun patchInvalidBodyGets400AndChangesNothing() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")
        val path = "/api/teams/$team/members/$memberId"

        assertEquals(HttpStatusCode.BadRequest, patchAs(admin, path, "{not json").status)
        assertEquals(HttpStatusCode.BadRequest, patchAs(admin, path, """{"ftePercent":0}""").status)
        assertEquals(HttpStatusCode.BadRequest, patchAs(admin, path, """{"ftePercent":101}""").status)
        assertEquals(HttpStatusCode.BadRequest, patchAs(admin, path, """{"role":"ADMIN"}""").status)
        assertEquals(MemberState("MEMBER", 100, false, true), memberState(team, memberId))
    }

    @Test
    fun managerCannotPromoteToManagerButAdminCan() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val path = "/api/teams/$team/members/$memberId"

        assertEquals(HttpStatusCode.NotFound, patchAs(managerId, path, """{"role":"MANAGER"}""").status)
        assertEquals("MEMBER", memberState(team, memberId).role)
        assertEquals(HttpStatusCode.NoContent, patchAs(admin, path, """{"role":"MANAGER"}""").status)
        assertEquals("MANAGER", memberState(team, memberId).role)
    }

    // DELETE /api/teams/{teamId}/members/{userId}

    @Test
    fun adminSoftRemovesMemberKeepingRow() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")

        assertEquals(HttpStatusCode.NoContent, deleteAs(admin, "/api/teams/$team/members/$memberId").status)
        assertEquals(false, memberState(team, memberId).active)
        assertEquals(1, memberCount(team))
    }

    @Test
    fun nonAdminManagerDeleteOfManagerGets404AdminGets204() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val otherManagerId = insertAccount("mgr2-${uniq()}@x.pt", companyId = company)
        addMember(team, otherManagerId, "MANAGER")
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val path = "/api/teams/$team/members/$otherManagerId"

        assertEquals(HttpStatusCode.NotFound, deleteAs(managerId, path).status)
        assertTrue(memberState(team, otherManagerId).active)
        assertEquals(HttpStatusCode.NoContent, deleteAs(admin, path).status)
        assertFalse(memberState(team, otherManagerId).active)
    }

    @Test
    fun removedMemberIsRestoredByPatchActiveAndListedActive() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")
        val path = "/api/teams/$team/members/$memberId"

        assertEquals(HttpStatusCode.NoContent, deleteAs(admin, path).status)
        assertEquals(HttpStatusCode.NoContent, patchAs(admin, path, """{"active":true}""").status)
        assertEquals(MemberState("MEMBER", 100, false, true), memberState(team, memberId))
        val listed = getAs(admin, "/api/teams/$team/members").jsonArray().first {
            it.jsonObject["userId"]!!.jsonPrimitive.content == memberId.toString()
        }.jsonObject
        assertEquals("true", listed["active"].toString())
    }

    @Test
    fun nonAdminManagerReactivatingManagerGets404() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val otherManagerId = insertAccount("mgr2-${uniq()}@x.pt", companyId = company)
        addMember(team, otherManagerId, "MANAGER")
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val path = "/api/teams/$team/members/$otherManagerId"
        assertEquals(HttpStatusCode.NoContent, deleteAs(admin, path).status)

        assertEquals(HttpStatusCode.NotFound, patchAs(managerId, path, """{"active":true}""").status)
        assertFalse(memberState(team, otherManagerId).active)
        assertEquals(HttpStatusCode.NoContent, patchAs(admin, path, """{"active":true}""").status)
        assertTrue(memberState(team, otherManagerId).active)
    }

    @Test
    fun patchWithUnchangedRoleStillAppliesFte() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")

        assertEquals(
            HttpStatusCode.NoContent,
            patchAs(admin, "/api/teams/$team/members/$memberId", """{"role":"MEMBER","ftePercent":40}""").status,
        )
        assertEquals(MemberState("MEMBER", 40, false, true), memberState(team, memberId))
    }

    @Test
    fun managerPatchSelfDeactivateGets400AndOtherPatchesStillOk() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val path = "/api/teams/$team/members/$managerId"

        assertEquals(HttpStatusCode.BadRequest, patchAs(managerId, path, """{"active":false}""").status)
        assertTrue(memberState(team, managerId).active)
        assertEquals(HttpStatusCode.NoContent, patchAs(managerId, path, """{"active":true}""").status)
        assertEquals(HttpStatusCode.NoContent, patchAs(managerId, path, """{"ftePercent":70}""").status)
        assertEquals(MemberState("MANAGER", 70, false, true), memberState(team, managerId))
    }

    @Test
    fun deleteRefusesSelfAndHidesOutsideReach() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")

        assertEquals(HttpStatusCode.BadRequest, deleteAs(managerId, "/api/teams/$team/members/$managerId").status)
        assertTrue(memberState(team, managerId).active)
        assertEquals(HttpStatusCode.NotFound, deleteAs(memberId, "/api/teams/$team/members/$managerId").status)
        assertEquals(HttpStatusCode.NotFound, deleteAs(memberId, "/api/teams/$team/members/$memberId").status)
        assertTrue(memberState(team, memberId).active)
    }
}
