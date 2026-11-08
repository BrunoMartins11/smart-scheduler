package app.integ

import app.addMember
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.routeTest
import app.sessionCookie
import app.testDs
import app.uniq
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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

import app.callAs
import java.sql.Connection
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.put
import kotlin.test.assertNotEquals
class ShiftTemplateIntegTest {

    private val dayBody = """{"name":"Dia","startTime":"08:00","endTime":"20:00","isNight":false,"minStaff":1,"maxStaff":3}"""
    private val nightBody = """{"name":"Noite","startTime":"20:00","endTime":"08:00","isNight":true,"minStaff":1,"maxStaff":2}"""

    private suspend fun HttpResponse.idOf(): String = Json.parseToJsonElement(bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

    private fun activeOf(id: String): Boolean =
        testDs.connection.use { c ->
            c.prepareStatement("SELECT active FROM shift_template WHERE id = ?").use { st ->
                st.setObject(1, UUID.fromString(id))
                st.executeQuery().use { it.next(); it.getBoolean(1) }
            }
        }

    // Makes the template used: a schedule_period plus one shift_slot that references it.
    private fun insertUsage(c: Connection, teamId: UUID, templateId: UUID) {
        val periodId = UUID.randomUUID()
        c.prepareStatement("INSERT INTO schedule_period (id, team_id, start_date, end_date) VALUES (?, ?, '2026-06-01', '2026-06-30')")
            .use { st -> st.setObject(1, periodId); st.setObject(2, teamId); st.executeUpdate() }
        c.prepareStatement("INSERT INTO shift_slot (period_id, template_id, date, min_staff, max_staff) VALUES (?, ?, '2026-06-10', 1, 1)")
            .use { st -> st.setObject(1, periodId); st.setObject(2, templateId); st.executeUpdate() }
    }

    private fun templateCount(teamId: UUID): Int =
        testDs.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM shift_template WHERE team_id = ?").use { st ->
                st.setObject(1, teamId)
                st.executeQuery().use { it.next(); it.getInt(1) }
            }
        }

    // POST /api/teams/{teamId}/shift-templates

    @Test
    fun managerCreatesTemplateGets201WithId() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val manager = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, manager, "MANAGER")

        val response = callAs(manager, "POST", "/api/teams/$team/shift-templates", dayBody)

        assertEquals(HttpStatusCode.Created, response.status)
        UUID.fromString(response.idOf())
    }

    @Test
    fun createInvalidMalformedOrOutsideReachGets400Or404() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val member = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, member, "MEMBER")
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)
        val path = "/api/teams/$team/shift-templates"

        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", path, dayBody.replace("\"minStaff\":1", "\"minStaff\":5")).status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", path, dayBody.replace("08:00", "8:00")).status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", path, "{not json").status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", path, """{"name":"Dia"}""").status)
        assertEquals(HttpStatusCode.NotFound, callAs(member, "POST", path, dayBody).status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "POST", path, dayBody).status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "POST", "/api/teams/not-a-uuid/shift-templates", dayBody).status)
    }

    // GET /api/teams/{teamId}/shift-templates

    @Test
    fun listReturnsContractShapeInOrderIncludingInactive() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val path = "/api/teams/$team/shift-templates"
        callAs(admin, "POST", path, nightBody)
        val dayId = callAs(admin, "POST", path, dayBody).idOf()
        callAs(admin, "DELETE", "$path/$dayId")

        val response = callAs(admin, "GET", path)

        assertEquals(HttpStatusCode.OK, response.status)
        val list: JsonArray = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(2, list.size)
        val day = list[0].jsonObject
        assertEquals(dayId, day["id"]!!.jsonPrimitive.content)
        assertEquals(setOf("id", "name", "startTime", "endTime", "isNight", "minStaff", "maxStaff", "active"), day.keys)
        assertEquals("08:00", day["startTime"]!!.jsonPrimitive.content)
        assertEquals("20:00", day["endTime"]!!.jsonPrimitive.content)
        assertEquals("false", day["active"].toString())
        assertEquals("Noite", list[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("true", list[1].jsonObject["isNight"].toString())
    }

    @Test
    fun listOutsideReachOrMalformedIdGets404() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val member = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, member, "MEMBER")
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.NotFound, callAs(member, "GET", "/api/teams/$team/shift-templates").status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "GET", "/api/teams/$team/shift-templates").status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "GET", "/api/teams/not-a-uuid/shift-templates").status)
    }

    // PUT /api/teams/{teamId}/shift-templates/{templateId}

    @Test
    fun putOnUnusedTemplateReturnsSameId() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val id = callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody).idOf()

        val response = callAs(admin, "PUT", "/api/teams/$team/shift-templates/$id", nightBody)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(id, response.idOf())
    }

    @Test
    fun putOnUsedTemplateReturnsNewIdAndDeactivatesOld() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val oldId = callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody).idOf()
        testDs.connection.use { insertUsage(it, team, UUID.fromString(oldId)) }

        val response = callAs(admin, "PUT", "/api/teams/$team/shift-templates/$oldId", nightBody)

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.idOf() == oldId)
        assertFalse(activeOf(oldId))
        assertTrue(activeOf(response.idOf()))
    }

    @Test
    fun putInvalidOrOutsideReachOrUnknownGets400Or404() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val id = callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody).idOf()
        val path = "/api/teams/$team/shift-templates/$id"
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "PUT", path, "{not json").status)
        assertEquals(HttpStatusCode.BadRequest, callAs(admin, "PUT", path, nightBody.replace("\"maxStaff\":2", "\"maxStaff\":0")).status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "PUT", path, nightBody).status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "PUT", "/api/teams/$team/shift-templates/${UUID.randomUUID()}", nightBody).status)
    }

    // DELETE and PATCH

    @Test
    fun deleteDeactivatesAndPatchReactivates() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val id = callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody).idOf()
        val path = "/api/teams/$team/shift-templates/$id"

        assertEquals(HttpStatusCode.NoContent, callAs(admin, "DELETE", path).status)
        assertFalse(activeOf(id))
        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PATCH", path, """{"active":true}""").status)
        assertTrue(activeOf(id))
    }

    @Test
    fun patchRejectsAnyOtherBodyWith400() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val id = callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody).idOf()
        val path = "/api/teams/$team/shift-templates/$id"
        callAs(admin, "DELETE", path)

        for (body in listOf("{}", """{"active":"yes"}""", """{"active":true,"name":"x"}""", "{not json")) {
            assertEquals(HttpStatusCode.BadRequest, callAs(admin, "PATCH", path, body).status, body)
        }
        assertFalse(activeOf(id))
    }

    @Test
    fun deleteAndPatchOutsideReachOrUnknownGets404() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val id = callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody).idOf()
        val path = "/api/teams/$team/shift-templates/$id"
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)
        val unknown = "/api/teams/$team/shift-templates/${UUID.randomUUID()}"

        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "DELETE", path).status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "PATCH", path, """{"active":false}""").status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "DELETE", unknown).status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "PATCH", "/api/teams/not-a-uuid/shift-templates/$id", """{"active":false}""").status)
        assertTrue(activeOf(id))
    }

    // Service-level scenarios kept at the HTTP surface.

    private fun body(name: String, end: String = "20:00", min: Int = 1, max: Int = 3) =
        """{"name":"$name","startTime":"08:00","endTime":"$end","isNight":false,"minStaff":$min,"maxStaff":$max}"""

    @Test
    fun listIsScopedToTeamOrderedByStartThenNameTrimmed() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val other = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val path = "/api/teams/$team/shift-templates"
        callAs(admin, "POST", path, nightBody)
        callAs(admin, "POST", path, body("B"))
        callAs(admin, "POST", path, body(" A "))
        callAs(admin, "POST", "/api/teams/$other/shift-templates", body("Elsewhere"))

        val names = Json.parseToJsonElement(callAs(admin, "GET", path).bodyAsText()).jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

        assertEquals(listOf("A", "B", "Noite"), names)
    }

    @Test
    fun invalidBodiesAreRejectedAndWriteNothing() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val path = "/api/teams/$team/shift-templates"
        val bad = listOf(
            body("   "),
            body("Dia", end = "24:00"),
            body("Dia", end = "20:00:00"),
            body("Dia", min = -1),
            body("Dia", min = 0, max = 0),
            body("Dia", max = 40_000),
        )

        for (b in bad) assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", path, b).status, b)
        assertEquals(0, templateCount(team))
    }

    @Test
    fun putThroughWrongTeamOrByOtherTeamManagerGets404() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val other = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val id = callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody).idOf()
        val managerOfOther = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(other, managerOfOther, "MANAGER")

        assertEquals(HttpStatusCode.NotFound, callAs(admin, "PUT", "/api/teams/$other/shift-templates/$id", nightBody).status)
        assertEquals(HttpStatusCode.NotFound, callAs(managerOfOther, "PUT", "/api/teams/$team/shift-templates/$id", nightBody).status)
        assertTrue(activeOf(id))
        assertEquals(1, templateCount(team))
    }

    // A slot insert in flight holds the template's FK lock until it commits. The PUT must wait for that commit
    // and then see the slot as used, so it creates a new version instead of editing the old row in place.
    @Test
    fun putWaitingOnInFlightSlotInsertCreatesNewVersion() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val id = callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody).idOf()
        val cookie = sessionCookie(admin)

        val raw = testDs.connection
        raw.autoCommit = false
        var response: HttpResponse? = null
        try {
            insertUsage(raw, team, UUID.fromString(id))
            val editor = thread {
                runBlocking {
                    response = client.put("/api/teams/$team/shift-templates/$id") {
                        header(HttpHeaders.Cookie, cookie)
                        contentType(ContentType.Application.Json)
                        setBody(nightBody)
                    }
                }
            }
            Thread.sleep(500)
            raw.commit()
            editor.join()
        } finally {
            raw.close()
        }

        val result = response!!
        assertEquals(HttpStatusCode.OK, result.status)
        val newId = result.idOf()
        assertNotEquals(id, newId)
        assertFalse(activeOf(id))
        assertTrue(activeOf(newId))
    }
}
