package app.integ

import app.addMember
import app.callAs
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.routeTest
import app.sessionCookie
import app.testDs
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
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AuditRow(val action: String, val entityType: String, val actor: String?, val payload: JsonObject)

class PeriodIntegTest {

    private val dayBody = """{"name":"Dia","startTime":"08:00","endTime":"20:00","isNight":false,"minStaff":1,"maxStaff":3}"""
    private val nightBody = """{"name":"Noite","startTime":"20:00","endTime":"08:00","isNight":true,"minStaff":1,"maxStaff":2}"""
    private val deadline = "2026-11-20T23:59:00Z"

    private fun tpl(name: String, start: String, end: String) =
        """{"name":"$name","startTime":"$start","endTime":"$end","isNight":false,"minStaff":1,"maxStaff":3}"""

    private fun periods(team: UUID) = "/api/teams/$team/periods"
    private fun range(start: String, end: String) = """{"startDate":"$start","endDate":"$end"}"""
    private fun statusBody(to: String, deadline: String? = null) =
        if (deadline == null) """{"status":"$to"}""" else """{"status":"$to","inputDeadline":"$deadline"}"""

    private suspend fun HttpResponse.idOf(): String = Json.parseToJsonElement(bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    private suspend fun HttpResponse.array(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray
    private fun JsonElement.text(key: String) = jsonObject[key]!!.jsonPrimitive.content

    private fun <T> sql(query: String, vararg args: Any, read: (ResultSet) -> T): T =
        testDs.connection.use { c ->
            c.prepareStatement(query).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { read(it) }
            }
        }

    private fun exec(query: String, vararg args: Any) =
        testDs.connection.use { c -> c.prepareStatement(query).use { st -> args.forEachIndexed { i, a -> st.setObject(i + 1, a) }; st.executeUpdate() } }

    private fun periodCount(team: UUID): Int = sql("SELECT count(*) FROM schedule_period WHERE team_id = ?", team) { it.next(); it.getInt(1) }
    private fun slotCount(period: String): Int = sql("SELECT count(*) FROM shift_slot WHERE period_id = ?", UUID.fromString(period)) { it.next(); it.getInt(1) }
    private fun periodStatus(period: String): String = sql("SELECT status FROM schedule_period WHERE id = ?", UUID.fromString(period)) { it.next(); it.getString(1) }
    private fun templateActive(id: String): Boolean = sql("SELECT active FROM shift_template WHERE id = ?", UUID.fromString(id)) { it.next(); it.getBoolean(1) }

    private fun auditOf(period: String): List<AuditRow> =
        sql("SELECT action, entity_type, actor_user_id::text, payload::text FROM audit_event WHERE entity_id = ? ORDER BY id", period) { rs ->
            buildList {
                while (rs.next()) add(AuditRow(rs.getString(1), rs.getString(2), rs.getString(3), Json.parseToJsonElement(rs.getString(4)).jsonObject))
            }
        }

    private fun teamWithAdmin(): Pair<UUID, UUID> {
        val company = insertCompany()
        val team = insertTeam(company)
        return team to insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
    }

    private suspend fun ApplicationTestBuilder.newPeriod(admin: UUID, team: UUID, start: String, end: String): String {
        val response = callAs(admin, "POST", periods(team), range(start, end))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.idOf()
    }

    private suspend fun ApplicationTestBuilder.setStatus(admin: UUID, team: UUID, period: String, body: String): HttpStatusCode =
        callAs(admin, "POST", "${periods(team)}/$period/status", body).status

    private suspend fun ApplicationTestBuilder.slotsOf(admin: UUID, team: UUID, period: String): JsonArray =
        callAs(admin, "GET", "${periods(team)}/$period/slots").array()

    private suspend fun ApplicationTestBuilder.periodRow(admin: UUID, team: UUID, period: String): JsonObject =
        callAs(admin, "GET", periods(team)).array().first { it.text("id") == period }.jsonObject

    // POST /api/teams/{teamId}/periods

    @Test
    fun createGeneratesDatesTimesActiveTemplatesWithCoverage() = routeTest {
        val (team, admin) = teamWithAdmin()
        val templates = "/api/teams/$team/shift-templates"
        callAs(admin, "POST", templates, dayBody)
        callAs(admin, "POST", templates, nightBody)
        val retired = callAs(admin, "POST", templates, tpl("Old", "06:00", "14:00")).idOf()
        callAs(admin, "DELETE", "$templates/$retired")

        val period = newPeriod(admin, team, "2026-06-01", "2026-06-03")

        val slots = slotsOf(admin, team, period)
        assertEquals(6, slots.size)
        assertTrue(slots.none { it.text("templateName") == "Old" })
        assertTrue(slots.all {
            it.text("minStaff") == "1" && it.text("maxStaff") == if (it.text("templateName") == "Dia") "3" else "2"
        })
        assertEquals(setOf("2026-06-01", "2026-06-02", "2026-06-03"), slots.map { it.text("date") }.toSet())
    }

    @Test
    fun createWithZeroActiveTemplatesIsOkAndHasNoSlots() = routeTest {
        val (team, admin) = teamWithAdmin()

        val period = newPeriod(admin, team, "2026-06-01", "2026-06-30")

        assertEquals(0, slotsOf(admin, team, period).size)
    }

    @Test
    fun invalidRangesAreRejectedAndWriteNothing() = routeTest {
        val (team, admin) = teamWithAdmin()
        callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody)
        val bad = listOf(
            range("2026-13-01", "2026-06-01"),
            range("2026-02-30", "2026-03-01"),
            range("2026-6-1", "2026-06-02"),
            range("2026-06-10", "2026-06-09"),
            range("2026-06-01", "2026-09-01"),
            "{not json",
            """{"startDate":"2026-06-01"}""",
        )

        for (body in bad) assertEquals(HttpStatusCode.BadRequest, callAs(admin, "POST", periods(team), body).status, body)
        assertEquals(0, periodCount(team))
        assertEquals(HttpStatusCode.Created, callAs(admin, "POST", periods(team), range("2026-06-01", "2026-08-31")).status)
        assertEquals(1, periodCount(team))
    }

    @Test
    fun overlappingPeriodIsConflictAndAdjacentIsOk() = routeTest {
        val (team, admin) = teamWithAdmin()
        newPeriod(admin, team, "2026-06-01", "2026-06-30")

        assertEquals(HttpStatusCode.Conflict, callAs(admin, "POST", periods(team), range("2026-06-15", "2026-07-10")).status)
        assertEquals(HttpStatusCode.Conflict, callAs(admin, "POST", periods(team), range("2026-05-20", "2026-06-01")).status)
        assertEquals(HttpStatusCode.Created, callAs(admin, "POST", periods(team), range("2026-07-01", "2026-07-10")).status)
        assertEquals(HttpStatusCode.Created, callAs(admin, "POST", periods(team), range("2026-05-01", "2026-05-31")).status)
        assertEquals(3, periodCount(team))
    }

    @Test
    fun createOutsideReachOrMalformedIdGets404() = routeTest {
        val (team, admin) = teamWithAdmin()
        val company = insertCompany()
        val member = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, member, "MEMBER")
        val otherTeam = insertTeam(company)
        val managerOfOther = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(otherTeam, managerOfOther, "MANAGER")
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        for (who in listOf(member, managerOfOther, foreignAdmin)) {
            assertEquals(HttpStatusCode.NotFound, callAs(who, "POST", periods(team), range("2026-06-01", "2026-06-30")).status)
        }
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "POST", "/api/teams/not-a-uuid/periods", range("2026-06-01", "2026-06-30")).status)
        assertEquals(0, periodCount(team))
    }

    // GET /api/teams/{teamId}/periods

    @Test
    fun listIsNewestStartFirstWithContractShapeAndOnlyThisTeam() = routeTest {
        val (team, admin) = teamWithAdmin()
        val other = insertTeam(insertCompany())
        newPeriod(admin, team, "2026-06-01", "2026-06-30")
        newPeriod(admin, team, "2026-08-01", "2026-08-31")
        newPeriod(admin, team, "2026-07-01", "2026-07-31")
        callAs(admin, "POST", periods(other), range("2026-09-01", "2026-09-30"))

        val response = callAs(admin, "GET", periods(team))

        assertEquals(HttpStatusCode.OK, response.status)
        val list = response.array()
        assertEquals(listOf("2026-08-01", "2026-07-01", "2026-06-01"), list.map { it.text("startDate") })
        assertEquals(setOf("id", "startDate", "endDate", "status", "inputDeadline"), list[0].jsonObject.keys)
        assertEquals("DRAFT", list[0].text("status"))
        assertEquals("2026-08-31", list[0].text("endDate"))
        assertTrue(list[0].jsonObject["inputDeadline"] is JsonNull)
    }

    @Test
    fun listOutsideReachGets404() = routeTest {
        val (team, _) = teamWithAdmin()
        val company = insertCompany()
        val member = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, member, "MEMBER")
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)

        assertEquals(HttpStatusCode.NotFound, callAs(member, "GET", periods(team)).status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "GET", periods(team)).status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "GET", "/api/teams/not-a-uuid/periods").status)
    }

    // Every route outside reach or with unknown ids answers 404, and changes nothing.
    @Test
    fun everyRouteOutsideReachOrUnknownGets404AndChangesNothing() = routeTest {
        val (team, admin) = teamWithAdmin()
        val company = insertCompany()
        val member = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, member, "MEMBER")
        val otherTeam = insertTeam(company)
        val managerOfOther = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(otherTeam, managerOfOther, "MANAGER")
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)
        callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody)
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-02")
        val slot = slotsOf(admin, team, period).first().text("id")
        val path = "${periods(team)}/$period"

        for (who in listOf(member, managerOfOther, foreignAdmin)) {
            assertEquals(HttpStatusCode.NotFound, callAs(who, "GET", "$path/slots").status)
            assertEquals(HttpStatusCode.NotFound, callAs(who, "DELETE", path).status)
            assertEquals(HttpStatusCode.NotFound, callAs(who, "POST", "$path/status", statusBody("OPEN", deadline)).status)
            assertEquals(HttpStatusCode.NotFound, callAs(who, "PATCH", "$path/slots/$slot", """{"minStaff":0,"maxStaff":1}""").status)
        }
        val unknown = UUID.randomUUID()
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "GET", "${periods(team)}/$unknown/slots").status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "GET", "${periods(team)}/not-a-uuid/slots").status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "DELETE", "${periods(team)}/$unknown").status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "POST", "${periods(team)}/$unknown/status", statusBody("OPEN", deadline)).status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "PATCH", "$path/slots/$unknown", """{"minStaff":0,"maxStaff":1}""").status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "GET", "/api/teams/not-a-uuid/periods").status)
        assertEquals("DRAFT", periodStatus(period))
        assertEquals(1, periodCount(team))
    }

    // POST /api/teams/{teamId}/periods/{periodId}/status

    @Test
    fun everyAllowedTransitionWritesAuditRowWithActionAndPayload() = routeTest {
        val (team, admin) = teamWithAdmin()
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-30")

        assertEquals(HttpStatusCode.NoContent, setStatus(admin, team, period, statusBody("OPEN", deadline)))
        assertEquals(HttpStatusCode.NoContent, setStatus(admin, team, period, statusBody("CLOSED")))
        assertEquals(HttpStatusCode.NoContent, setStatus(admin, team, period, statusBody("OPEN")))

        assertEquals("OPEN", periodStatus(period))
        val rows = auditOf(period)
        assertEquals(listOf("OPEN", "CLOSE", "REOPEN"), rows.map { it.action })
        assertTrue(rows.all { it.entityType == "schedule_period" && it.actor == admin.toString() })
        assertEquals(listOf("DRAFT", "OPEN", "CLOSED"), rows.map { it.payload["from"]!!.jsonPrimitive.content })
        assertEquals(listOf("OPEN", "CLOSED", "OPEN"), rows.map { it.payload["to"]!!.jsonPrimitive.content })
        assertEquals(listOf(deadline, deadline, deadline), rows.map { it.payload["inputDeadline"]!!.jsonPrimitive.content })
    }

    @Test
    fun disallowedTransitionsGet409AndUnknownStatusGets400() = routeTest {
        val (team, admin) = teamWithAdmin()
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-30")

        for (to in listOf("CLOSED", "DRAFT", "PUBLISHED")) assertEquals(HttpStatusCode.Conflict, setStatus(admin, team, period, statusBody(to)), to)
        assertEquals(HttpStatusCode.BadRequest, setStatus(admin, team, period, statusBody("DONE")))
        assertEquals(HttpStatusCode.BadRequest, setStatus(admin, team, period, statusBody("open")))
        assertEquals(HttpStatusCode.NoContent, setStatus(admin, team, period, statusBody("OPEN", deadline)))

        for (to in listOf("OPEN", "DRAFT", "PUBLISHED")) assertEquals(HttpStatusCode.Conflict, setStatus(admin, team, period, statusBody(to)), to)
        assertEquals(HttpStatusCode.NoContent, setStatus(admin, team, period, statusBody("CLOSED")))

        for (to in listOf("CLOSED", "DRAFT", "PUBLISHED")) assertEquals(HttpStatusCode.Conflict, setStatus(admin, team, period, statusBody(to)), to)
        assertEquals("CLOSED", periodStatus(period))
    }

    @Test
    fun draftToOpenWithoutValidDeadlineGets400AndStaysDraft() = routeTest {
        val (team, admin) = teamWithAdmin()
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-30")

        assertEquals(HttpStatusCode.BadRequest, setStatus(admin, team, period, statusBody("OPEN")))
        assertEquals(HttpStatusCode.BadRequest, setStatus(admin, team, period, """{"status":"OPEN","inputDeadline":"next friday"}"""))
        assertEquals("DRAFT", periodStatus(period))
        assertTrue(auditOf(period).isEmpty())
    }

    @Test
    fun reopenKeepsStoredDeadlineUnlessNewOneIsSent() = routeTest {
        val (team, admin) = teamWithAdmin()
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-30")
        setStatus(admin, team, period, statusBody("OPEN", deadline))
        setStatus(admin, team, period, statusBody("CLOSED"))

        assertEquals(HttpStatusCode.NoContent, setStatus(admin, team, period, statusBody("OPEN")))
        assertEquals(deadline, periodRow(admin, team, period).text("inputDeadline"))

        setStatus(admin, team, period, statusBody("CLOSED"))
        assertEquals(HttpStatusCode.NoContent, setStatus(admin, team, period, statusBody("OPEN", "2026-12-01T09:00:00Z")))
        assertEquals("2026-12-01T09:00:00Z", periodRow(admin, team, period).text("inputDeadline"))
    }

    // Two requests race OPEN -> CLOSED; the row lock makes the second see CLOSED and answer 409.
    @Test
    fun twoConcurrentCloseRequestsGiveOneNoContentAndOneConflict() = routeTest {
        val (team, admin) = teamWithAdmin()
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-30")
        setStatus(admin, team, period, statusBody("OPEN", deadline))
        val cookie = sessionCookie(admin)
        val url = "${periods(team)}/$period/status"
        val results = ConcurrentLinkedQueue<HttpStatusCode>()
        val go = CountDownLatch(1)

        val workers = List(2) {
            thread {
                go.await()
                runBlocking {
                    results.add(client.post(url) {
                        header(HttpHeaders.Cookie, cookie)
                        contentType(ContentType.Application.Json)
                        setBody(statusBody("CLOSED"))
                    }.status)
                }
            }
        }
        go.countDown()
        workers.forEach { it.join() }

        assertEquals(2, results.size)
        assertEquals(setOf(HttpStatusCode.NoContent, HttpStatusCode.Conflict), results.toSet())
        assertEquals("CLOSED", periodStatus(period))
    }

    // DELETE /api/teams/{teamId}/periods/{periodId}

    @Test
    fun deleteDraftRemovesPeriodAndSlotsAndWritesAudit() = routeTest {
        val (team, admin) = teamWithAdmin()
        callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody)
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-02")
        assertEquals(2, slotCount(period))

        assertEquals(HttpStatusCode.NoContent, callAs(admin, "DELETE", "${periods(team)}/$period").status)

        assertEquals(0, periodCount(team))
        assertEquals(0, slotCount(period))
        assertEquals(listOf("DELETE"), auditOf(period).map { it.action })
    }

    @Test
    fun deleteNonDraftGets409AndKeepsPeriod() = routeTest {
        val (team, admin) = teamWithAdmin()
        callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody)
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-02")
        setStatus(admin, team, period, statusBody("OPEN", deadline))

        assertEquals(HttpStatusCode.Conflict, callAs(admin, "DELETE", "${periods(team)}/$period").status)

        assertEquals("OPEN", periodStatus(period))
        assertEquals(2, slotCount(period))
    }

    // GET /api/teams/{teamId}/periods/{periodId}/slots

    @Test
    fun slotsAreOrderedByDateThenStartThenNameAndKeepDeactivatedTemplates() = routeTest {
        val (team, admin) = teamWithAdmin()
        val templates = "/api/teams/$team/shift-templates"
        callAs(admin, "POST", templates, tpl("B", "08:00", "16:00"))
        callAs(admin, "POST", templates, tpl("A", "08:00", "16:00"))
        val night = callAs(admin, "POST", templates, nightBody).idOf()
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-02")
        callAs(admin, "DELETE", "$templates/$night")

        val response = callAs(admin, "GET", "${periods(team)}/$period/slots")

        assertEquals(HttpStatusCode.OK, response.status)
        val slots = response.array()
        assertEquals(6, slots.size)
        assertEquals(
            listOf("2026-06-01", "2026-06-01", "2026-06-01", "2026-06-02", "2026-06-02", "2026-06-02"),
            slots.map { it.text("date") },
        )
        assertEquals(listOf("A", "B", "Noite", "A", "B", "Noite"), slots.map { it.text("templateName") })
        val first = slots[2].jsonObject
        assertEquals(setOf("id", "date", "templateId", "templateName", "startTime", "endTime", "isNight", "minStaff", "maxStaff", "defaultMinStaff", "defaultMaxStaff"), first.keys)
        assertEquals("20:00", first["startTime"]!!.jsonPrimitive.content)
        assertEquals("08:00", first["endTime"]!!.jsonPrimitive.content)
        assertEquals("true", first["isNight"].toString())
        assertEquals("2", first["maxStaff"].toString())
    }

    // PATCH /api/teams/{teamId}/periods/{periodId}/slots/{slotId}

    @Test
    fun patchSlotUpdatesCoverageAndRejectsInvalidValues() = routeTest {
        val (team, admin) = teamWithAdmin()
        callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody)
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-02")
        val slot = slotsOf(admin, team, period).first().text("id")
        val path = "${periods(team)}/$period/slots/$slot"

        assertEquals(HttpStatusCode.NoContent, callAs(admin, "PATCH", path, """{"minStaff":0,"maxStaff":5}""").status)
        val patched = slotsOf(admin, team, period).first()
        assertEquals("0", patched.text("minStaff"))
        assertEquals("5", patched.text("maxStaff"))
        assertEquals("1", patched.text("defaultMinStaff"))
        assertEquals("3", patched.text("defaultMaxStaff"))
        // The untouched slot on the second day keeps min == default.
        val untouched = slotsOf(admin, team, period).last()
        assertEquals(untouched.text("defaultMinStaff"), untouched.text("minStaff"))
        assertEquals(untouched.text("defaultMaxStaff"), untouched.text("maxStaff"))

        for (body in listOf("""{"minStaff":4,"maxStaff":3}""", """{"minStaff":-1,"maxStaff":3}""", """{"minStaff":0,"maxStaff":0}""", """{"minStaff":0,"maxStaff":40000}""", "{not json")) {
            assertEquals(HttpStatusCode.BadRequest, callAs(admin, "PATCH", path, body).status, body)
        }
        assertEquals("5", slotsOf(admin, team, period).first().text("maxStaff"))
    }

    @Test
    fun patchSlotOfAnotherPeriodOrUnknownGets404() = routeTest {
        val (team, admin) = teamWithAdmin()
        callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody)
        val first = newPeriod(admin, team, "2026-06-01", "2026-06-02")
        val second = newPeriod(admin, team, "2026-07-01", "2026-07-01")
        val firstSlot = slotsOf(admin, team, first).first().text("id")
        val secondSlot = slotsOf(admin, team, second).first().text("id")
        val body = """{"minStaff":0,"maxStaff":1}"""

        assertEquals(HttpStatusCode.NotFound, callAs(admin, "PATCH", "${periods(team)}/$first/slots/$secondSlot", body).status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "PATCH", "${periods(team)}/$second/slots/$firstSlot", body).status)
        assertEquals(HttpStatusCode.NotFound, callAs(admin, "PATCH", "${periods(team)}/$first/slots/${UUID.randomUUID()}", body).status)
    }

    @Test
    fun patchSlotOnPublishedPeriodGets409() = routeTest {
        val (team, admin) = teamWithAdmin()
        callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody)
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-02")
        val slot = slotsOf(admin, team, period).first().text("id")
        exec("UPDATE schedule_period SET status = 'PUBLISHED' WHERE id = ?", UUID.fromString(period))

        assertEquals(HttpStatusCode.Conflict, callAs(admin, "PATCH", "${periods(team)}/$period/slots/$slot", """{"minStaff":0,"maxStaff":9}""").status)
        assertEquals("3", slotsOf(admin, team, period).first().text("maxStaff"))
    }

    // A template used by a period edits as a new version, and the period's slots keep the old one.
    @Test
    fun editingUsedTemplateCreatesNewVersionAndSlotsKeepOldOne() = routeTest {
        val (team, admin) = teamWithAdmin()
        val oldId = callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody).idOf()
        val period = newPeriod(admin, team, "2026-06-01", "2026-06-02")

        val response = callAs(admin, "PUT", "/api/teams/$team/shift-templates/$oldId", nightBody)

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.idOf() == oldId)
        assertFalse(templateActive(oldId))
        val slots = slotsOf(admin, team, period)
        assertTrue(slots.all { it.text("templateId") == oldId })
        // The new version has defaults 1/2; the slot keeps the 1/3 it was generated from.
        assertTrue(slots.all { it.text("defaultMinStaff") == "1" && it.text("defaultMaxStaff") == "3" })
    }
}
