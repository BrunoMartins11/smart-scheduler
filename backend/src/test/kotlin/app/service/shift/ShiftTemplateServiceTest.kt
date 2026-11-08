package app.service.shift

import app.addMember
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.model.ShiftTemplateBody
import app.persistence.shift.ShiftTemplateRepository
import app.persistence.team.TeamRepository
import app.persistence.user.UserRepository
import app.service.auth.AuthService
import app.service.team.TeamService
import app.testDs
import app.uniq
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShiftTemplateServiceTest {

    private val auth = AuthService(UserRepository(testDs))
    private val shifts = ShiftTemplateService(ShiftTemplateRepository(testDs), TeamService(TeamRepository(testDs)))

    private fun body(
        name: String = "Dia",
        start: String = "08:00",
        end: String = "20:00",
        night: Boolean = false,
        min: Int = 1,
        max: Int = 3,
    ) = ShiftTemplateBody(name, start, end, night, min, max)

    private fun templateActive(id: UUID): Boolean =
        testDs.connection.use { c ->
            c.prepareStatement("SELECT active FROM shift_template WHERE id = ?").use { st ->
                st.setObject(1, id)
                st.executeQuery().use { it.next(); it.getBoolean(1) }
            }
        }

    private fun templateCount(teamId: UUID): Int =
        testDs.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM shift_template WHERE team_id = ?").use { st ->
                st.setObject(1, teamId)
                st.executeQuery().use { it.next(); it.getInt(1) }
            }
        }

    // Makes the template used: a schedule_period plus one shift_slot that references it.
    private fun useTemplate(teamId: UUID, templateId: UUID) {
        testDs.connection.use { c ->
            val periodId = UUID.randomUUID()
            c.prepareStatement(
                "INSERT INTO schedule_period (id, team_id, start_date, end_date) VALUES (?, ?, '2026-06-01', '2026-06-30')"
            ).use { st ->
                st.setObject(1, periodId)
                st.setObject(2, teamId)
                st.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO shift_slot (period_id, template_id, date, min_staff, max_staff) VALUES (?, ?, '2026-06-10', 1, 1)"
            ).use { st ->
                st.setObject(1, periodId)
                st.setObject(2, templateId)
                st.executeUpdate()
            }
        }
    }

    // create / list

    @Test
    fun createListsOnlyThisTeamOrderedByStartThenName() {
        val company = insertCompany()
        val team = insertTeam(company)
        val other = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val night = assertIs<ShiftTemplateResult.Saved>(shifts.create(admin, team, body("Noite", "20:00", "08:00", true))).id
        val dayB = assertIs<ShiftTemplateResult.Saved>(shifts.create(admin, team, body("B", "08:00", "20:00"))).id
        val dayA = assertIs<ShiftTemplateResult.Saved>(shifts.create(admin, team, body(" A ", "08:00", "20:00"))).id
        assertIs<ShiftTemplateResult.Saved>(shifts.create(admin, other, body("Elsewhere")))

        val listed = shifts.list(admin, team)!!
        assertEquals(listOf(dayA, dayB, night), listed.map { it.id })
        assertEquals("A", listed[0].name)
        assertEquals(true, listed[2].isNight)
    }

    @Test
    fun invalidInputIsRejectedAndWritesNothing() {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!

        val bad = listOf(
            body(name = "   "),
            body(start = "8:00"),
            body(end = "24:00"),
            body(end = "20:00:00"),
            body(min = -1, max = 3),
            body(min = 0, max = 0),
            body(min = 4, max = 3),
            body(min = 1, max = 40_000),
        )
        for (b in bad) assertEquals(ShiftTemplateResult.Invalid, shifts.create(admin, team, b), b.toString())
        assertEquals(0, templateCount(team))
    }

    @Test
    fun createOutsideReachIsNotFound() {
        val company = insertCompany()
        val team = insertTeam(company)
        val member = auth.currentUser(insertAccount("member-${uniq()}@x.pt", companyId = company))!!
        addMember(team, member.id, "MEMBER")
        val foreignAdmin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true))!!

        assertEquals(ShiftTemplateResult.NotFound, shifts.create(member, team, body()))
        assertEquals(ShiftTemplateResult.NotFound, shifts.create(foreignAdmin, team, body()))
        assertEquals(0, templateCount(team))
    }

    // edit

    @Test
    fun editUnusedUpdatesInPlaceAndKeepsId() {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val id = assertIs<ShiftTemplateResult.Saved>(shifts.create(admin, team, body())).id

        assertEquals(ShiftTemplateResult.Saved(id), shifts.edit(admin, team, id, body("Manhã", "07:00", "15:00", min = 2, max = 4)))

        val only = shifts.list(admin, team)!!.single()
        assertEquals(id, only.id)
        assertEquals("Manhã", only.name)
        assertEquals(2, only.minStaff)
        assertEquals(4, only.maxStaff)
        assertEquals(1, templateCount(team))
    }

    @Test
    fun editUsedCreatesNewVersionAndDeactivatesOldInOneGo() {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val oldId = assertIs<ShiftTemplateResult.Saved>(shifts.create(admin, team, body())).id
        useTemplate(team, oldId)

        val newId = assertIs<ShiftTemplateResult.Saved>(shifts.edit(admin, team, oldId, body("Tarde", "14:00", "22:00"))).id

        assertNotEquals(oldId, newId)
        assertEquals(2, templateCount(team))
        assertFalse(templateActive(oldId))
        assertTrue(templateActive(newId))
        val listed = shifts.list(admin, team)!!.associateBy { it.id }
        assertEquals("Dia", listed.getValue(oldId).name)
        assertEquals("Tarde", listed.getValue(newId).name)
    }

    @Test
    fun editInvalidOrOutsideReachChangesNothing() {
        val company = insertCompany()
        val team = insertTeam(company)
        val other = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val id = assertIs<ShiftTemplateResult.Saved>(shifts.create(admin, team, body())).id
        useTemplate(team, id)
        val manager = auth.currentUser(insertAccount("mgr-${uniq()}@x.pt", companyId = company))!!
        addMember(other, manager.id, "MANAGER")

        assertEquals(ShiftTemplateResult.Invalid, shifts.edit(admin, team, id, body(min = 5, max = 1)))
        assertEquals(ShiftTemplateResult.NotFound, shifts.edit(manager, team, id, body("Hack")))
        // A template from another team is not reachable through this team's URL.
        assertEquals(ShiftTemplateResult.NotFound, shifts.edit(admin, other, id, body("Hack")))
        assertEquals(ShiftTemplateResult.NotFound, shifts.edit(admin, team, UUID.randomUUID(), body("Hack")))

        assertEquals(1, templateCount(team))
        assertTrue(templateActive(id))
        assertEquals("Dia", shifts.list(admin, team)!!.single { it.id == id }.name)
    }

    // setActive

    @Test
    fun deactivateAndReactivateKeepsRow() {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val id = assertIs<ShiftTemplateResult.Saved>(shifts.create(admin, team, body())).id

        assertEquals(ShiftTemplateResult.Ok, shifts.setActive(admin, team, id, active = false))
        assertFalse(shifts.list(admin, team)!!.single().active)
        assertEquals(ShiftTemplateResult.Ok, shifts.setActive(admin, team, id, active = true))
        assertTrue(shifts.list(admin, team)!!.single().active)
    }

    @Test
    fun setActiveOutsideReachOrUnknownIsNotFound() {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val id = assertIs<ShiftTemplateResult.Saved>(shifts.create(admin, team, body())).id
        val foreignAdmin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true))!!

        assertEquals(ShiftTemplateResult.NotFound, shifts.setActive(foreignAdmin, team, id, active = false))
        assertEquals(ShiftTemplateResult.NotFound, shifts.setActive(admin, team, UUID.randomUUID(), active = false))
        assertTrue(templateActive(id))
    }

    @Test
    fun listIsNullForMemberAndOtherCompany() {
        val company = insertCompany()
        val team = insertTeam(company)
        val member = auth.currentUser(insertAccount("member-${uniq()}@x.pt", companyId = company))!!
        addMember(team, member.id, "MEMBER")
        val foreignAdmin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true))!!

        assertNull(shifts.list(member, team))
        assertNull(shifts.list(foreignAdmin, team))
    }
}
