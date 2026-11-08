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
import io.ktor.client.request.patch
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
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AvailabilityIntegTest {

    private val dayBody = """{"name":"Dia","startTime":"08:00","endTime":"20:00","isNight":false,"minStaff":1,"maxStaff":3}"""
    private val nightBody = """{"name":"Noite","startTime":"20:00","endTime":"08:00","isNight":true,"minStaff":1,"maxStaff":2}"""

    private fun periods(team: UUID) = "/api/teams/$team/periods"
    private fun calendarPath(team: UUID, period: String, user: UUID) = "${periods(team)}/$period/members/$user/availability"
    private fun marks(vararg m: Pair<String, String?>): String =
        """{"marks":[${m.joinToString(",") { (id, kind) -> """{"slotId":"$id","kind":${kind?.let { "\"$it\"" } ?: "null"}}""" }}]}"""

    private suspend fun HttpResponse.idOf(): String = Json.parseToJsonElement(bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    private suspend fun HttpResponse.array(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray
    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonElement.text(key: String) = jsonObject[key]!!.jsonPrimitive.content
    private fun JsonElement.kind(): String? = jsonObject["kind"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
    private fun JsonElement.enteredByManager() = jsonObject["enteredByManager"].toString() == "true"

    private fun <T> sql(query: String, vararg args: Any, read: (ResultSet) -> T): T =
        testDs.connection.use { c ->
            c.prepareStatement(query).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { read(it) }
            }
        }

    private fun exec(query: String, vararg args: Any) =
        testDs.connection.use { c -> c.prepareStatement(query).use { st -> args.forEachIndexed { i, a -> st.setObject(i + 1, a) }; st.executeUpdate() } }

    private fun teamMemberId(team: UUID, user: UUID): String =
        sql("SELECT id FROM team_member WHERE team_id = ? AND user_id = ?", team, user) { it.next(); it.getString(1) }

    private fun auditCount(team: UUID, user: UUID): Int =
        sql("SELECT count(*) FROM audit_event WHERE entity_type = 'availability' AND action = 'SET_ON_BEHALF' AND entity_id = ?", teamMemberId(team, user)) { it.next(); it.getInt(1) }

    private fun rowCount(slot: String): Int = sql("SELECT count(*) FROM availability WHERE slot_id = ?", UUID.fromString(slot)) { it.next(); it.getInt(1) }

    private fun periodStatus(period: String): String = sql("SELECT status FROM schedule_period WHERE id = ?", UUID.fromString(period)) { it.next(); it.getString(1) }

    // A shift member: works_shifts is set, which addMember does not do.
    private fun shiftMember(team: UUID, user: UUID, active: Boolean = true) =
        exec("INSERT INTO team_member (team_id, user_id, role, works_shifts, fte_percent, active) VALUES (?, ?, 'MEMBER', true, 100, ?)", team, user, active)

    private class World(val company: UUID, val team: UUID, val admin: UUID)

    private fun world(): World {
        val company = insertCompany()
        val team = insertTeam(company)
        return World(company, team, insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))
    }

    private fun account(company: UUID) = insertAccount("u-${uniq()}@x.pt", companyId = company)

    private suspend fun ApplicationTestBuilder.templates(admin: UUID, team: UUID) {
        callAs(admin, "POST", "/api/teams/$team/shift-templates", dayBody)
        callAs(admin, "POST", "/api/teams/$team/shift-templates", nightBody)
    }

    private suspend fun ApplicationTestBuilder.newPeriod(admin: UUID, team: UUID, start: String = "2026-06-01", end: String = "2026-06-02"): String {
        val response = callAs(admin, "POST", periods(team), """{"startDate":"$start","endDate":"$end"}""")
        assertEquals(HttpStatusCode.Created, response.status)
        return response.idOf()
    }

    private suspend fun ApplicationTestBuilder.setStatus(admin: UUID, team: UUID, period: String, body: String): HttpStatusCode =
        callAs(admin, "POST", "${periods(team)}/$period/status", body).status

    private suspend fun ApplicationTestBuilder.openPeriod(admin: UUID, team: UUID): String {
        val period = newPeriod(admin, team)
        assertEquals(HttpStatusCode.NoContent, setStatus(admin, team, period, """{"status":"OPEN","inputDeadline":"2026-11-20T23:59:00Z"}"""))
        return period
    }

    private suspend fun ApplicationTestBuilder.slotIds(admin: UUID, team: UUID, period: String): List<String> =
        callAs(admin, "GET", "${periods(team)}/$period/slots").array().map { it.text("id") }

    private suspend fun ApplicationTestBuilder.calendar(who: UUID, team: UUID, period: String, user: UUID): HttpResponse =
        callAs(who, "GET", calendarPath(team, period, user))

    private suspend fun ApplicationTestBuilder.kindsOf(who: UUID, team: UUID, period: String, user: UUID): Map<String, String?> =
        calendar(who, team, period, user).obj()["slots"]!!.jsonArray.associate { it.text("id") to it.kind() }

    // GET /api/teams/{teamId}/periods/{periodId}/members/{userId}/availability

    @Test
    fun memberReadsOwnCalendarWhileOpenAndIsEditable() = routeTest {
        val w = world()
        val member = account(w.company)
        shiftMember(w.team, member)
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)

        val response = calendar(member, w.team, period, member)

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.obj()
        assertEquals(member.toString(), body["member"]!!.jsonObject["userId"]!!.jsonPrimitive.content)
        assertEquals("OPEN", body["period"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(true, body["editable"]!!.jsonPrimitive.content.toBoolean())
        val slots = body["slots"]!!.jsonArray
        assertEquals(4, slots.size)
        assertEquals(setOf("id", "date", "templateName", "startTime", "endTime", "isNight", "kind", "enteredByManager"), slots[0].jsonObject.keys)
        assertEquals(listOf("Dia", "Noite", "Dia", "Noite"), slots.map { it.text("templateName") })
        assertTrue(slots.all { it.kind() == null && !it.enteredByManager() })
    }

    @Test
    fun memberPatchesSingleBulkClearsAndKindsRoundTrip() = routeTest {
        val w = world()
        val member = account(w.company)
        shiftMember(w.team, member)
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)
        val (s1, s2, s3, s4) = slotIds(w.admin, w.team, period)
        val path = calendarPath(w.team, period, member)

        assertEquals(HttpStatusCode.NoContent, callAs(member, "PATCH", path, marks(s1 to "AVAILABLE")).status)
        assertEquals(mapOf(s1 to "AVAILABLE", s2 to null, s3 to null, s4 to null), kindsOf(member, w.team, period, member))

        assertEquals(HttpStatusCode.NoContent, callAs(member, "PATCH", path, marks(s2 to "IF_NEEDED", s3 to "AVAILABLE", s4 to "IF_NEEDED")).status)
        assertEquals(mapOf(s1 to "AVAILABLE", s2 to "IF_NEEDED", s3 to "AVAILABLE", s4 to "IF_NEEDED"), kindsOf(member, w.team, period, member))

        assertEquals(HttpStatusCode.NoContent, callAs(member, "PATCH", path, marks(s1 to null, s2 to null)).status)
        assertEquals(mapOf(s1 to null, s2 to null, s3 to "AVAILABLE", s4 to "IF_NEEDED"), kindsOf(member, w.team, period, member))
        assertEquals(0, rowCount(s1))
        assertEquals(0, auditCount(w.team, member))
    }

    @Test
    fun memberWhenClosedGets409AndManagerEntersOnBehalfWithOneAudit() = routeTest {
        val w = world()
        val member = account(w.company)
        val manager = account(w.company)
        addMember(w.team, manager, "MANAGER")
        shiftMember(w.team, member)
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)
        assertEquals(HttpStatusCode.NoContent, setStatus(w.admin, w.team, period, """{"status":"CLOSED"}"""))
        val (s1, s2, _, _) = slotIds(w.admin, w.team, period)
        val path = calendarPath(w.team, period, member)

        assertEquals(HttpStatusCode.Conflict, callAs(member, "PATCH", path, marks(s1 to "AVAILABLE")).status)
        assertEquals(0, rowCount(s1))
        assertEquals(HttpStatusCode.NoContent, callAs(manager, "PATCH", path, marks(s1 to "AVAILABLE", s2 to "IF_NEEDED")).status)

        val viaManager = calendar(manager, w.team, period, member).obj()
        assertEquals(true, viaManager["editable"]!!.jsonPrimitive.content.toBoolean())
        val slot = viaManager["slots"]!!.jsonArray.first { it.text("id") == s1 }
        assertEquals("AVAILABLE", slot.kind())
        assertTrue(slot.enteredByManager())
        assertEquals(1, auditCount(w.team, member))
        val viaMember = calendar(member, w.team, period, member).obj()["slots"]!!.jsonArray.first { it.text("id") == s1 }
        assertTrue(viaMember.enteredByManager())
    }

    @Test
    fun publishedGets409ForMemberAndManager() = routeTest {
        val w = world()
        val member = account(w.company)
        val manager = account(w.company)
        addMember(w.team, manager, "MANAGER")
        shiftMember(w.team, member)
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)
        val s1 = slotIds(w.admin, w.team, period).first()
        exec("UPDATE schedule_period SET status = 'PUBLISHED' WHERE id = ?", UUID.fromString(period))
        val path = calendarPath(w.team, period, member)

        assertEquals(false, calendar(member, w.team, period, member).obj()["editable"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(HttpStatusCode.Conflict, callAs(member, "PATCH", path, marks(s1 to "AVAILABLE")).status)
        assertEquals(HttpStatusCode.Conflict, callAs(manager, "PATCH", path, marks(s1 to "AVAILABLE")).status)
        assertEquals(0, rowCount(s1))
    }

    @Test
    fun memberCannotReadOrPatchAnotherMember() = routeTest {
        val w = world()
        val memberA = account(w.company)
        val memberB = account(w.company)
        shiftMember(w.team, memberA)
        shiftMember(w.team, memberB)
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)
        val s1 = slotIds(w.admin, w.team, period).first()

        assertEquals(HttpStatusCode.NotFound, calendar(memberA, w.team, period, memberB).status)
        assertEquals(HttpStatusCode.NotFound, callAs(memberA, "PATCH", calendarPath(w.team, period, memberB), marks(s1 to "AVAILABLE")).status)
        assertEquals(0, rowCount(s1))
    }

    // Each of these answers 404: the target is not a shift member of the team, or the caller is outside reach.
    @Test
    fun nonShiftInactiveOtherTeamOtherCompanyAndDraftGet404() = routeTest {
        val w = world()
        val manager = account(w.company)
        addMember(w.team, manager, "MANAGER")
        val nonShift = account(w.company)
        addMember(w.team, nonShift, "MEMBER")
        val inactive = account(w.company)
        shiftMember(w.team, inactive, active = false)
        val otherTeam = insertTeam(w.company)
        val elsewhere = account(w.company)
        shiftMember(otherTeam, elsewhere)
        val foreignAdmin = insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true)
        val member = account(w.company)
        shiftMember(w.team, member)
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)
        val s1 = slotIds(w.admin, w.team, period).first()
        val draft = newPeriod(w.admin, w.team, "2026-07-01", "2026-07-02")

        for (target in listOf(nonShift, inactive, elsewhere)) {
            assertEquals(HttpStatusCode.NotFound, calendar(manager, w.team, period, target).status, "target $target")
            assertEquals(HttpStatusCode.NotFound, callAs(manager, "PATCH", calendarPath(w.team, period, target), marks(s1 to "AVAILABLE")).status)
        }
        assertEquals(HttpStatusCode.NotFound, calendar(nonShift, w.team, period, nonShift).status)
        assertEquals(HttpStatusCode.NotFound, calendar(inactive, w.team, period, inactive).status)
        assertEquals(HttpStatusCode.NotFound, calendar(foreignAdmin, w.team, period, member).status)
        assertEquals(HttpStatusCode.NotFound, callAs(foreignAdmin, "PATCH", calendarPath(w.team, period, member), marks(s1 to "AVAILABLE")).status)
        assertEquals(HttpStatusCode.NotFound, calendar(member, w.team, draft, member).status)
        assertEquals(HttpStatusCode.NotFound, callAs(member, "PATCH", calendarPath(w.team, draft, member), marks(s1 to "AVAILABLE")).status)
        assertEquals(HttpStatusCode.OK, calendar(w.admin, w.team, draft, member).status)
        assertEquals(false, calendar(w.admin, w.team, draft, member).obj()["editable"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, rowCount(s1))
    }

    @Test
    fun slotOfAnotherPeriodGets404AndNothingIsWritten() = routeTest {
        val w = world()
        val member = account(w.company)
        shiftMember(w.team, member)
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)
        val other = newPeriod(w.admin, w.team, "2026-07-01", "2026-07-02")
        setStatus(w.admin, w.team, other, """{"status":"OPEN","inputDeadline":"2026-11-20T23:59:00Z"}""")
        val (own, _, _, _) = slotIds(w.admin, w.team, period)
        val foreign = slotIds(w.admin, w.team, other).first()

        val response = callAs(member, "PATCH", calendarPath(w.team, period, member), marks(own to "AVAILABLE", foreign to "AVAILABLE"))

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(0, rowCount(own))
        assertEquals(0, rowCount(foreign))
    }

    @Test
    fun invalidMarkListsGet400AndWriteNothing() = routeTest {
        val w = world()
        val member = account(w.company)
        shiftMember(w.team, member)
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)
        val s1 = slotIds(w.admin, w.team, period).first()
        val path = calendarPath(w.team, period, member)
        val oversized = """{"marks":[${(1..501).joinToString(",") { """{"slotId":"$s1","kind":"AVAILABLE"}""" }}]}"""

        for (body in listOf(
            """{"marks":[]}""",
            oversized,
            marks(s1 to "MAYBE"),
            marks(s1 to "AVAILABLE", s1 to "IF_NEEDED"),
            """{"marks":[{"slotId":"not-a-uuid","kind":"AVAILABLE"}]}""",
            "{}",
            "{not json",
        )) {
            assertEquals(HttpStatusCode.BadRequest, callAs(member, "PATCH", path, body).status, body.take(60))
        }
        assertEquals(0, rowCount(s1))
    }

    // GET /api/teams/{teamId}/periods/{periodId}/submissions

    @Test
    fun submissionStatusesFollowWhoEnteredTheLastMarks() = routeTest {
        val w = world()
        val a = account(w.company)
        val b = account(w.company)
        val outsider = account(w.company)
        addMember(w.team, outsider, "MEMBER")
        shiftMember(w.team, a)
        shiftMember(w.team, b)
        exec("UPDATE user_account SET display_name = 'Ana' WHERE id = ?", a)
        exec("UPDATE user_account SET display_name = 'Bea' WHERE id = ?", b)
        val manager = account(w.company)
        addMember(w.team, manager, "MANAGER")
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)
        val (s1, s2, _, _) = slotIds(w.admin, w.team, period)
        val subs = "${periods(w.team)}/$period/submissions"

        suspend fun statuses() = callAs(w.admin, "GET", subs).array().associate { it.text("displayName") to it.text("status") }
        assertEquals(mapOf("Ana" to "NOT_SUBMITTED", "Bea" to "NOT_SUBMITTED"), statuses())

        callAs(manager, "PATCH", calendarPath(w.team, period, a), marks(s1 to "AVAILABLE", s2 to "IF_NEEDED"))
        assertEquals(mapOf("Ana" to "MANAGER", "Bea" to "NOT_SUBMITTED"), statuses())

        callAs(a, "PATCH", calendarPath(w.team, period, a), marks(s1 to "IF_NEEDED"))
        assertEquals(mapOf("Ana" to "MANAGER", "Bea" to "NOT_SUBMITTED"), statuses())

        callAs(a, "PATCH", calendarPath(w.team, period, a), marks(s2 to "AVAILABLE"))
        assertEquals(mapOf("Ana" to "MEMBER", "Bea" to "NOT_SUBMITTED"), statuses())

        callAs(b, "PATCH", calendarPath(w.team, period, b), marks(s1 to "AVAILABLE"))
        assertEquals(mapOf("Ana" to "MEMBER", "Bea" to "MEMBER"), statuses())
        assertEquals(listOf("Ana", "Bea"), callAs(w.admin, "GET", subs).array().map { it.text("displayName") })

        assertEquals(HttpStatusCode.NotFound, callAs(a, "GET", subs).status)
        assertEquals(HttpStatusCode.NotFound, callAs(manager, "GET", "${periods(w.team)}/${UUID.randomUUID()}/submissions").status)
    }

    // GET /api/me/availability-periods

    @Test
    fun myPeriodsListsOnlyShiftTeamsWithoutDraftsAndSubmittedFlips() = routeTest {
        val w = world()
        val member = account(w.company)
        val teamB = insertTeam(w.company)
        addMember(teamB, member, "MEMBER")
        val teamC = insertTeam(w.company)
        shiftMember(teamC, member)
        shiftMember(w.team, member)
        templates(w.admin, w.team)
        val open = openPeriod(w.admin, w.team)
        newPeriod(w.admin, w.team, "2026-07-01", "2026-07-02")
        val hiddenInB = newPeriod(w.admin, teamB, "2026-08-01", "2026-08-02")
        setStatus(w.admin, teamB, hiddenInB, """{"status":"OPEN","inputDeadline":"2026-11-20T23:59:00Z"}""")
        val closed = newPeriod(w.admin, teamC, "2026-05-01", "2026-05-02")
        setStatus(w.admin, teamC, closed, """{"status":"OPEN","inputDeadline":"2026-11-20T23:59:00Z"}""")
        setStatus(w.admin, teamC, closed, """{"status":"CLOSED"}""")

        val before = callAs(member, "GET", "/api/me/availability-periods")
        assertEquals(HttpStatusCode.OK, before.status)
        val rows = before.array()
        assertEquals(listOf(open, closed), rows.map { it.text("periodId") })
        assertEquals(setOf("teamId", "teamName", "periodId", "startDate", "endDate", "status", "inputDeadline", "submitted"), rows[0].jsonObject.keys)
        assertEquals(listOf(false, false), rows.map { it.jsonObject["submitted"]!!.jsonPrimitive.content.toBoolean() })

        val s1 = slotIds(w.admin, w.team, open).first()
        assertEquals(HttpStatusCode.NoContent, callAs(member, "PATCH", calendarPath(w.team, open, member), marks(s1 to "AVAILABLE")).status)
        val after = callAs(member, "GET", "/api/me/availability-periods").array()
        assertEquals(listOf(true, false), after.map { it.jsonObject["submitted"]!!.jsonPrimitive.content.toBoolean() })
    }

    // Concurrency: a close holds the period row. A PATCH waits for it and must see CLOSED, so it answers 409.
    @Test
    fun patchWaitingOnInFlightCloseAnswers409() = routeTest {
        val w = world()
        val member = account(w.company)
        shiftMember(w.team, member)
        templates(w.admin, w.team)
        val period = openPeriod(w.admin, w.team)
        val s1 = slotIds(w.admin, w.team, period).first()
        val cookie = sessionCookie(member)
        val url = calendarPath(w.team, period, member)

        val raw = testDs.connection
        raw.autoCommit = false
        var response: HttpResponse? = null
        try {
            raw.prepareStatement("UPDATE schedule_period SET status = 'CLOSED' WHERE id = ?").use { st ->
                st.setObject(1, UUID.fromString(period))
                st.executeUpdate()
            }
            val editor = thread {
                runBlocking {
                    response = client.patch(url) {
                        header(HttpHeaders.Cookie, cookie)
                        contentType(ContentType.Application.Json)
                        setBody(marks(s1 to "AVAILABLE"))
                    }
                }
            }
            Thread.sleep(500)
            raw.commit()
            editor.join()
        } finally {
            raw.close()
        }

        assertEquals(HttpStatusCode.Conflict, response!!.status)
        assertEquals(0, rowCount(s1))
        assertEquals("CLOSED", periodStatus(period))
    }
}
