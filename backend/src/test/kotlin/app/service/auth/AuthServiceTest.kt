package app.service.auth

import app.addMember
import app.ensureDefaultCompany
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.persistence.user.UserRepository
import app.testDs
import app.uniq
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {

    private val auth = AuthService(UserRepository(testDs))

    init {
        ensureDefaultCompany()
    }

    @Test
    fun verifiedEmailClaimsInvite() {
        val email = "ana-${uniq()}@hospital.pt"
        val id = insertAccount(email)
        val sub = "google-oauth2|${uniq()}"
        assertEquals(Login.Ok(id), auth.resolveLogin(sub, email, emailVerified = true))
        // The claimed sub is saved, so the next login matches by sub.
        assertEquals(Login.Ok(id), auth.resolveLogin(sub, "other@x.pt", emailVerified = false))
    }

    @Test
    fun unverifiedEmailIsRefused() {
        val email = "bruno-${uniq()}@hospital.pt"
        insertAccount(email)
        assertEquals(Login.NoInvite, auth.resolveLogin("auth0|${uniq()}", email, emailVerified = false))
    }

    @Test
    fun unknownAccountIsRefused() {
        assertEquals(Login.NoInvite, auth.resolveLogin("auth0|${uniq()}", "nobody-${uniq()}@x.pt", emailVerified = true))
    }

    @Test
    fun laterLoginMatchesBySub() {
        val sub = "auth0|${uniq()}"
        val id = insertAccount("carla-${uniq()}@hospital.pt", sub = sub)
        assertEquals(Login.Ok(id), auth.resolveLogin(sub, null, emailVerified = false))
    }

    @Test
    fun deactivatedAccountIsRefused() {
        val email = "dina-${uniq()}@hospital.pt"
        val sub = "auth0|${uniq()}"
        insertAccount(email, sub = sub, deactivated = true)
        assertIs<Login.Deactivated>(auth.resolveLogin(sub, email, emailVerified = true))
    }

    @Test
    fun currentUserIsNullForUnknownId() {
        assertNull(auth.currentUser(UUID.randomUUID()))
    }

    @Test
    fun currentUserReportsDeactivatedAccount() {
        val id = insertAccount("gone-${uniq()}@x.pt", companyId = insertCompany(), deactivated = true)
        assertTrue(auth.currentUser(id)!!.deactivated)
    }

    @Test
    fun currentUserListsOnlyActiveManagerTeamsAndAdminFlag() {
        val company = insertCompany()
        val managed = insertTeam(company)
        val memberOf = insertTeam(company)
        val inactive = insertTeam(company)
        val userId = insertAccount("user-${uniq()}@x.pt", companyId = company, admin = true)
        addMember(managed, userId, "MANAGER")
        addMember(memberOf, userId, "MEMBER")
        addMember(inactive, userId, "MANAGER", active = false)

        val user = auth.currentUser(userId)!!
        assertEquals(setOf(managed), user.managedTeamIds)
        assertTrue(user.isCompanyAdmin)
        assertFalse(user.deactivated)
    }
}
