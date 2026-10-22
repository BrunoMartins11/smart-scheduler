package app

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthTest {

    private val ds = connectDatabase(testConfig()).also { migrate(it) }

    // Unique per test run, so tests can share one database.
    private fun uniq() = UUID.randomUUID().toString().take(8)

    private fun insertAccount(
        email: String,
        sub: String? = null,
        deactivated: Boolean = false,
        companyId: UUID? = null,
        admin: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        ds.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO user_account (id, company_id, email, auth_subject, display_name, deactivated_at, is_company_admin) " +
                    "VALUES (?, COALESCE(?, (SELECT id FROM company LIMIT 1)), ?, ?, 'Test', ?, ?)"
            ).use { st ->
                st.setObject(1, id)
                st.setObject(2, companyId)
                st.setString(3, email)
                st.setString(4, sub)
                if (deactivated) st.setTimestamp(5, java.sql.Timestamp(0)) else st.setNull(5, java.sql.Types.TIMESTAMP)
                st.setBoolean(6, admin)
                st.executeUpdate()
            }
        }
        return id
    }

    init {
        ds.connection.use { c ->
            c.createStatement().executeUpdate("INSERT INTO company (name) SELECT 'Acme' WHERE NOT EXISTS (SELECT 1 FROM company)")
        }
    }

    @Test
    fun verifiedEmailClaimsInvite() {
        val email = "ana-${uniq()}@hospital.pt"
        val id = insertAccount(email)
        val sub = "google-oauth2|${uniq()}"
        assertEquals(Login.Ok(id), resolveLogin(ds, sub, email, emailVerified = true))
        // The claimed sub is saved, so the next login matches by sub.
        assertEquals(Login.Ok(id), resolveLogin(ds, sub, "other@x.pt", emailVerified = false))
    }

    @Test
    fun unverifiedEmailIsRefused() {
        val email = "bruno-${uniq()}@hospital.pt"
        insertAccount(email)
        assertEquals(Login.NoInvite, resolveLogin(ds, "auth0|${uniq()}", email, emailVerified = false))
    }

    @Test
    fun unknownAccountIsRefused() {
        assertEquals(Login.NoInvite, resolveLogin(ds, "auth0|${uniq()}", "nobody-${uniq()}@x.pt", emailVerified = true))
    }

    @Test
    fun laterLoginMatchesBySub() {
        val sub = "auth0|${uniq()}"
        val id = insertAccount("carla-${uniq()}@hospital.pt", sub = sub)
        assertEquals(Login.Ok(id), resolveLogin(ds, sub, null, emailVerified = false))
    }

    @Test
    fun deactivatedAccountIsRefused() {
        val email = "dina-${uniq()}@hospital.pt"
        val sub = "auth0|${uniq()}"
        insertAccount(email, sub = sub, deactivated = true)
        assertIs<Login.Deactivated>(resolveLogin(ds, sub, email, emailVerified = true))
    }

    private fun insertCompany(): UUID {
        val id = UUID.randomUUID()
        ds.connection.use { c ->
            c.prepareStatement("INSERT INTO company (id, name) VALUES (?, ?)").use { st ->
                st.setObject(1, id)
                st.setString(2, "Co-${uniq()}")
                st.executeUpdate()
            }
        }
        return id
    }

    private fun insertTeam(companyId: UUID): UUID {
        val id = UUID.randomUUID()
        ds.connection.use { c ->
            c.prepareStatement("INSERT INTO team (id, company_id, name) VALUES (?, ?, ?)").use { st ->
                st.setObject(1, id)
                st.setObject(2, companyId)
                st.setString(3, "Team-${uniq()}")
                st.executeUpdate()
            }
        }
        return id
    }

    private fun addMember(teamId: UUID, userId: UUID, role: String, active: Boolean = true) {
        ds.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO team_member (team_id, user_id, role, fte_percent, active) VALUES (?, ?, ?, 100, ?)"
            ).use { st ->
                st.setObject(1, teamId)
                st.setObject(2, userId)
                st.setString(3, role)
                st.setBoolean(4, active)
                st.executeUpdate()
            }
        }
    }

    @Test
    fun adminCanManageTeamInOwnCompany() {
        val company = insertCompany()
        val admin = loadCurrentUser(ds, insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        assertTrue(admin.canManage(ds, insertTeam(company)))
    }

    @Test
    fun adminCannotManageTeamOfAnotherCompany() {
        val companyA = insertCompany()
        val companyB = insertCompany()
        val adminA = loadCurrentUser(ds, insertAccount("admin-${uniq()}@x.pt", companyId = companyA, admin = true))!!
        assertFalse(adminA.canManage(ds, insertTeam(companyB)))
    }

    @Test
    fun adminCannotManageMissingTeam() {
        val company = insertCompany()
        val admin = loadCurrentUser(ds, insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        assertFalse(admin.canManage(ds, UUID.randomUUID()))
    }

    @Test
    fun managerCanManageOnlyOwnTeam() {
        val company = insertCompany()
        val own = insertTeam(company)
        val other = insertTeam(company)
        val userId = insertAccount("manager-${uniq()}@x.pt", companyId = company)
        addMember(own, userId, "MANAGER")
        addMember(other, insertAccount("manager-${uniq()}@x.pt", companyId = company), "MANAGER")
        val manager = loadCurrentUser(ds, userId)!!
        assertEquals(setOf(own), manager.managedTeamIds)
        assertTrue(manager.canManage(ds, own))
        assertFalse(manager.canManage(ds, other))
    }

    @Test
    fun memberCannotManage() {
        val company = insertCompany()
        val team = insertTeam(company)
        val userId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, userId, "MEMBER")
        val member = loadCurrentUser(ds, userId)!!
        assertEquals(emptySet(), member.managedTeamIds)
        assertFalse(member.canManage(ds, team))
    }

    @Test
    fun inactiveManagerMembershipDoesNotCount() {
        val company = insertCompany()
        val team = insertTeam(company)
        val userId = insertAccount("manager-${uniq()}@x.pt", companyId = company)
        addMember(team, userId, "MANAGER", active = false)
        assertFalse(loadCurrentUser(ds, userId)!!.canManage(ds, team))
    }
}
