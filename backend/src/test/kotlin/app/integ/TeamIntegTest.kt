package app.integ

import app.MemberState
import app.addMember
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.memberCount
import app.memberState
import app.routeTest
import app.teamCompany
import app.testDs
import app.callAs
import app.uniq
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TeamIntegTest {

    private fun inviteBody(email: String, role: String = "MEMBER") =
        """{"email":"$email","displayName":"Ana","role":"$role"}"""

    // POST /api/teams

    @Test
    fun adminCreatesTeamAndGets201() = routeTest {
        val company = insertCompany()
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)

        val response = callAs(admin, "POST", "/api/teams", """{"name":"Urgência"}""")

        assertEquals(HttpStatusCode.Created, response.status)
        val id = UUID.fromString(Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(company, teamCompany(id))
    }

    @Test
    fun nonAdminCreatesTeamGets404() = routeTest {
        val company = insertCompany()
        val member = insertAccount("member-${uniq()}@x.pt", companyId = company)

        assertEquals(HttpStatusCode.NotFound, callAs(member, "POST", "/api/teams", """{"name":"Blocos"}""").status)
    }

    @Test
    fun blankTeamNameGets400() = routeTest {
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", "/api/teams", """{"name":"   "}""").status)
    }

    @Test
    fun malformedTeamJsonGets400() = routeTest {
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", "/api/teams", "{not json").status)
    }

    // POST /api/teams/{teamId}/invites

    @Test
    fun managerInvitesMemberGets201() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val manager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, manager, "MANAGER")

        val response = callAs(manager, "POST", "/api/teams/$team/invites", inviteBody("new-${uniq()}@x.pt"))

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(2, memberCount(team))
    }

    @Test
    fun inviteToTeamOutsideReachGets404() = routeTest {
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)
        val otherTeam = insertTeam(insertCompany())

        val response = callAs(admin, "POST", "/api/teams/$otherTeam/invites", inviteBody("new-${uniq()}@x.pt"))

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(0, memberCount(otherTeam))
    }

    @Test
    fun inviteToMalformedTeamIdGets404() = routeTest {
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        val response = callAs(admin, "POST", "/api/teams/not-a-uuid/invites", inviteBody("new-${uniq()}@x.pt"))

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun duplicateInviteEmailGets409() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val email = "dup-${uniq()}@x.pt"
        insertAccount(email, companyId = company)

        val response = callAs(admin, "POST", "/api/teams/$team/invites", inviteBody(email.uppercase()))

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

        val response = callAs(admin, "POST", "/api/teams/$team/invites", inviteBody(email))

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(1, memberCount(team))
    }

    @Test
    fun invalidInviteRoleGets400() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)

        val response = callAs(admin, "POST", "/api/teams/$team/invites", inviteBody("new-${uniq()}@x.pt", role = "ADMIN"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, memberCount(team))
    }

    @Test
    fun malformedInviteBodyGets400() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)

        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", "/api/teams/$team/invites", "{not json").status)
        // Missing required displayName.
        assertEquals(
            HttpStatusCode.BadRequest,
            callAs(admin, "POST", "/api/teams/$team/invites", """{"email":"x@x.pt","role":"MEMBER"}""").status,
        )
    }

    // GET /api/teams

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

        val adminIds = callAs(admin, "GET", "/api/teams").jsonArray().ids()
        assertTrue(mine.toString() in adminIds && inactive.toString() in adminIds)
        assertFalse(foreign.toString() in adminIds)

        val response = callAs(memberId, "GET", "/api/teams")
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
            val response = callAs(viewer, "GET", "/api/teams/$team/members")
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

        assertEquals(HttpStatusCode.NotFound, callAs(managerId, "GET", "/api/teams/$team/members").status)
        assertEquals(HttpStatusCode.NotFound, callAs(memberId, "GET", "/api/teams/$team/members").status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "GET", "/api/teams/$team/members").status)
        assertEquals(HttpStatusCode.NotFound, callAs(memberId, "GET", "/api/teams/not-a-uuid/members").status)
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

        val response = callAs(managerId, "PATCH", "/api/teams/$team/members/$memberId", """{"ftePercent":60}""")

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

        assertEquals(HttpStatusCode.NotFound, callAs(managerId, "PATCH", "/api/teams/$otherTeam/members/$memberId", """{"ftePercent":60}""").status)
        assertEquals(HttpStatusCode.NotFound, callAs(managerId, "PATCH", "/api/teams/$team/members/${UUID.randomUUID()}", """{"ftePercent":60}""").status)
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

        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "PATCH", path, "{not json").status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "PATCH", path, """{"ftePercent":0}""").status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "PATCH", path, """{"ftePercent":101}""").status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "PATCH", path, """{"role":"ADMIN"}""").status)
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

        assertEquals(HttpStatusCode.NotFound, callAs(managerId, "PATCH", path, """{"role":"MANAGER"}""").status)
        assertEquals("MEMBER", memberState(team, memberId).role)
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PATCH", path, """{"role":"MANAGER"}""").status)
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

        assertEquals(HttpStatusCode.NoContent, callAs(admin, "DELETE", "/api/teams/$team/members/$memberId").status)
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

        assertEquals(HttpStatusCode.NotFound, callAs(managerId, "DELETE", path).status)
        assertTrue(memberState(team, otherManagerId).active)
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "DELETE", path).status)
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

        assertEquals(HttpStatusCode.NoContent, callAs(admin, "DELETE", path).status)
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PATCH", path, """{"active":true}""").status)
        assertEquals(MemberState("MEMBER", 100, false, true), memberState(team, memberId))
        val listed = callAs(admin, "GET", "/api/teams/$team/members").jsonArray().first {
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
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "DELETE", path).status)

        assertEquals(HttpStatusCode.NotFound, callAs(managerId, "PATCH", path, """{"active":true}""").status)
        assertFalse(memberState(team, otherManagerId).active)
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PATCH", path, """{"active":true}""").status)
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
            callAs(admin, "PATCH", "/api/teams/$team/members/$memberId", """{"role":"MEMBER","ftePercent":40}""").status,
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

        assertEquals(HttpStatusCode.BadRequest, callAs(managerId, "PATCH", path, """{"active":false}""").status)
        assertTrue(memberState(team, managerId).active)
        assertEquals(HttpStatusCode.NoContent, callAs(managerId, "PATCH", path, """{"active":true}""").status)
        assertEquals(HttpStatusCode.NoContent, callAs(managerId, "PATCH", path, """{"ftePercent":70}""").status)
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

        assertEquals(HttpStatusCode.BadRequest, callAs(managerId, "DELETE", "/api/teams/$team/members/$managerId").status)
        assertTrue(memberState(team, managerId).active)
        assertEquals(HttpStatusCode.NotFound, callAs(memberId, "DELETE", "/api/teams/$team/members/$managerId").status)
        assertEquals(HttpStatusCode.NotFound, callAs(memberId, "DELETE", "/api/teams/$team/members/$memberId").status)
        assertTrue(memberState(team, memberId).active)
    }

    // /api/teams/{teamId}/rules

    private val pilotJson = """{"minRestBetweenShiftsMin":720,"maxConsecutiveWorkMin":720,"maxAvgWeeklyWorkMin":2880,""" +
        """"avgReferenceWeeks":26,"minWeeklyRestMin":1440,"maxConsecutiveNights":null,"restAfterNightRunMin":null}"""
    private val customJson = """{"minRestBetweenShiftsMin":60,"maxConsecutiveWorkMin":480,"maxAvgWeeklyWorkMin":2400,""" +
        """"avgReferenceWeeks":13,"minWeeklyRestMin":1000,"maxConsecutiveNights":4,"restAfterNightRunMin":600}"""

    @Test
    fun getRulesReturnsPilotDefaultsForManagerAndAdmin() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val manager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, manager, "MANAGER")

        for (userId in listOf(admin, manager)) {
            val response = callAs(userId, "GET", "/api/teams/$team/rules")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(Json.parseToJsonElement(pilotJson), Json.parseToJsonElement(response.bodyAsText()))
        }
    }

    @Test
    fun getRulesHidesOutsideReachAndBadIdsWithNotFound() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val member = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, member, "MEMBER")
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.NotFound, callAs(member, "GET", "/api/teams/$team/rules").status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "GET", "/api/teams/$team/rules").status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "GET", "/api/teams/not-a-uuid/rules").status)
    }

    @Test
    fun putRulesReplacesAllAndCanClearNullableRules() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)

        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PUT", "/api/teams/$team/rules", customJson).status)
        assertEquals(Json.parseToJsonElement(customJson), Json.parseToJsonElement(callAs(admin, "GET", "/api/teams/$team/rules").bodyAsText()))

        val omitted = """{"minRestBetweenShiftsMin":100,"maxConsecutiveWorkMin":200,"maxAvgWeeklyWorkMin":300,"avgReferenceWeeks":1,"minWeeklyRestMin":400}"""
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PUT", "/api/teams/$team/rules", omitted).status)
        val json = Json.parseToJsonElement(callAs(admin, "GET", "/api/teams/$team/rules").bodyAsText()).jsonObject
        assertEquals(100, json["minRestBetweenShiftsMin"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, json["maxConsecutiveNights"])
        assertEquals(JsonNull, json["restAfterNightRunMin"])
    }

    @Test
    fun putRulesInvalidOrMalformedGets400AndChangesNothing() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val path = "/api/teams/$team/rules"
        val bad = listOf(
            customJson.replace("\"avgReferenceWeeks\":13", "\"avgReferenceWeeks\":0"),
            customJson.replace("\"avgReferenceWeeks\":13", "\"avgReferenceWeeks\":53"),
            customJson.replace("\"minRestBetweenShiftsMin\":60", "\"minRestBetweenShiftsMin\":0"),
            customJson.replace("\"maxConsecutiveNights\":4", "\"maxConsecutiveNights\":-1"),
            customJson.replace("\"maxConsecutiveNights\":4", "\"maxConsecutiveNights\":40000"),
            customJson.replace("\"maxConsecutiveWorkMin\":480", "\"maxConsecutiveWorkMin\":-1"),
            customJson.replace("\"maxAvgWeeklyWorkMin\":2400", "\"maxAvgWeeklyWorkMin\":0"),
            customJson.replace("\"restAfterNightRunMin\":600", "\"restAfterNightRunMin\":0"),
            customJson.replace("\"minWeeklyRestMin\":1000", "\"minWeeklyRestMin\":null"),
            """{"minRestBetweenShiftsMin":60}""",
            "{not json",
        )

        for (body in bad) assertEquals(HttpStatusCode.BadRequest, callAs(admin, "PUT", path, body).status, body)
        assertEquals(Json.parseToJsonElement(pilotJson), Json.parseToJsonElement(callAs(admin, "GET", path).bodyAsText()))
    }

    @Test
    fun putRulesOutsideReachGets404AndChangesNothing() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val otherManager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(insertTeam(company), otherManager, "MANAGER")
        val member = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, member, "MEMBER")
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.NotFound, callAs(otherManager, "PUT", "/api/teams/$team/rules", customJson).status)
        assertEquals(HttpStatusCode.NotFound, callAs(member, "PUT", "/api/teams/$team/rules", customJson).status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "PUT", "/api/teams/$team/rules", customJson).status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "PUT", "/api/teams/not-a-uuid/rules", customJson).status)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        assertEquals(Json.parseToJsonElement(pilotJson), Json.parseToJsonElement(callAs(admin, "GET", "/api/teams/$team/rules").bodyAsText()))
    }

    @Test
    fun copyRulesNeedsBothTeamsAndSameCompany() = routeTest {
        val company = insertCompany()
        val source = insertTeam(company)
        val target = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PUT", "/api/teams/$source/rules", customJson).status)
        val copyBody = """{"fromTeamId":"$source"}"""

        val partialManager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(target, partialManager, "MANAGER")
        assertEquals(HttpStatusCode.NotFound, callAs(partialManager, "POST", "/api/teams/$target/rules/copy", copyBody).status)

        val bothManager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(source, bothManager, "MANAGER")
        addMember(target, bothManager, "MANAGER")
        assertEquals(HttpStatusCode.NoContent, callAs(bothManager, "POST", "/api/teams/$target/rules/copy", copyBody).status)
        assertEquals(Json.parseToJsonElement(customJson), Json.parseToJsonElement(callAs(admin, "GET", "/api/teams/$target/rules").bodyAsText()))

        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "POST", "/api/teams/$target/rules/copy", copyBody).status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "POST", "/api/teams/$target/rules/copy", """{"fromTeamId":"${UUID.randomUUID()}"}""").status)
    }

    @Test
    fun copyRulesBadBodyOrIdGets400Or404() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)

        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", "/api/teams/$team/rules/copy", """{"fromTeamId":"nope"}""").status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", "/api/teams/$team/rules/copy", "{not json").status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "POST", "/api/teams/not-a-uuid/rules/copy", """{"fromTeamId":"$team"}""").status)
    }

    // Service-level scenarios that the HTTP surface also has to keep.

    private fun timezone(teamId: UUID): String =
        testDs.connection.use { c ->
            c.prepareStatement("SELECT timezone FROM team WHERE id = ?").use { st ->
                st.setObject(1, teamId)
                st.executeQuery().use { it.next(); it.getString(1) }
            }
        }

    private fun setTimezone(teamId: UUID, tz: String) {
        testDs.connection.use { c ->
            c.prepareStatement("UPDATE team SET timezone = ? WHERE id = ?").use { st ->
                st.setString(1, tz)
                st.setObject(2, teamId)
                st.executeUpdate()
            }
        }
    }

    @Test
    fun inactiveManagerAndMissingTeamGet404() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val inactiveManager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, inactiveManager, "MANAGER", active = false)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)

        assertEquals(HttpStatusCode.NotFound, callAs(inactiveManager, "GET", "/api/teams/$team/members").status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "GET", "/api/teams/${UUID.randomUUID()}/members").status)
    }

    @Test
    fun managerInviteRulesAndInvalidInviteFieldsAddNothing() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val otherTeam = insertTeam(company)
        val manager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, manager, "MANAGER")
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val email = "new-${uniq()}@x.pt"
        val path = "/api/teams/$team/invites"

        assertEquals(HttpStatusCode.NotFound, callAs(manager, "POST", path, inviteBody(email, role = "MANAGER")).status)
        assertEquals(HttpStatusCode.NotFound, callAs(manager, "POST", "/api/teams/$otherTeam/invites", inviteBody(email)).status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", path, """{"email":"$email","displayName":"Bia","role":"MEMBER","ftePercent":0}""").status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", path, """{"email":"$email","displayName":"  ","role":"MEMBER"}""").status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", path, """{"email":"nope","displayName":"Bia","role":"MEMBER"}""").status)
        assertEquals(1, memberCount(team))
        assertEquals(0, memberCount(otherTeam))
    }

    @Test
    fun adminPatchesWorksShiftsAndRoleTogether() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")

        assertEquals(
            HttpStatusCode.NoContent,
            callAs(admin, "PATCH", "/api/teams/$team/members/$memberId", """{"ftePercent":50,"worksShifts":true,"role":"MANAGER"}""").status,
        )
        assertEquals(MemberState("MANAGER", 50, true, true), memberState(team, memberId))
    }

    @Test
    fun managerCannotDemoteOtherManagerButAdminCan() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val otherManagerId = insertAccount("mgr2-${uniq()}@x.pt", companyId = company)
        addMember(team, otherManagerId, "MANAGER")
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val path = "/api/teams/$team/members/$otherManagerId"

        assertEquals(HttpStatusCode.NotFound, callAs(managerId, "PATCH", path, """{"role":"MEMBER"}""").status)
        assertEquals("MANAGER", memberState(team, otherManagerId).role)
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PATCH", path, """{"role":"MEMBER"}""").status)
        assertEquals("MEMBER", memberState(team, otherManagerId).role)
    }

    @Test
    fun managerOfOwnTeamPutsRulesAndZeroNightsIsAccepted() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val manager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, manager, "MANAGER")
        val noNights = customJson.replace("\"maxConsecutiveNights\":4", "\"maxConsecutiveNights\":0")

        assertEquals(HttpStatusCode.NoContent, callAs(manager, "PUT", "/api/teams/$team/rules", noNights).status)
        val json = Json.parseToJsonElement(callAs(manager, "GET", "/api/teams/$team/rules").bodyAsText()).jsonObject
        assertEquals(0, json["maxConsecutiveNights"]!!.jsonPrimitive.int)
    }

    @Test
    fun copyRulesKeepsTargetTimezoneAndNeverCrossesCompanies() = routeTest {
        val companyA = insertCompany()
        val companyB = insertCompany()
        val source = insertTeam(companyA)
        val target = insertTeam(companyA)
        val foreignTarget = insertTeam(companyB)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = companyA, admin = true)
        val adminB = insertAccount("admin-${uniq()}@x.pt", companyId = companyB, admin = true)
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PUT", "/api/teams/$source/rules", customJson).status)
        setTimezone(target, "Europe/Madrid")
        val copyBody = """{"fromTeamId":"$source"}"""

        assertEquals(HttpStatusCode.NoContent, callAs(admin, "POST", "/api/teams/$target/rules/copy", copyBody).status)
        assertEquals(Json.parseToJsonElement(customJson), Json.parseToJsonElement(callAs(admin, "GET", "/api/teams/$target/rules").bodyAsText()))
        assertEquals("Europe/Madrid", timezone(target))

        assertEquals(HttpStatusCode.NotFound, callAs(admin, "POST", "/api/teams/$foreignTarget/rules/copy", copyBody).status)
        assertEquals(HttpStatusCode.NotFound, callAs(adminB, "POST", "/api/teams/$target/rules/copy", copyBody).status)
        assertEquals(Json.parseToJsonElement(pilotJson), Json.parseToJsonElement(callAs(adminB, "GET", "/api/teams/$foreignTarget/rules").bodyAsText()))
    }
}
