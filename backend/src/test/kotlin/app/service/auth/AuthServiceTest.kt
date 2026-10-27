package app.service.auth

import app.accountRow
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

    private val users = UserRepository(testDs)
    private val auth = AuthService(users)

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
    fun deactivatedInviteIsRefusedEvenWhenUnclaimed() {
        val email = "eva-${uniq()}@hospital.pt"
        insertAccount(email, sub = null, deactivated = true)
        assertIs<Login.Deactivated>(auth.resolveLogin("auth0|${uniq()}", email, emailVerified = true))
    }

    @Test
    fun secondClaimOfSameInviteLoses() {
        val id = insertAccount("fabio-${uniq()}@hospital.pt")
        val subA = "auth0|${uniq()}"
        val subB = "auth0|${uniq()}"
        assertTrue(users.claimInvite(id, subA))
        assertFalse(users.claimInvite(id, subB))
        assertEquals(subA, accountRow(id).authSubject)
    }

    @Test
    fun sameEmailInvitedByTwoCompaniesClaimsOldestInvite() {
        val email = "gil-${uniq()}@hospital.pt"
        val older = insertAccount(email, companyId = insertCompany())
        val newer = insertAccount(email, companyId = insertCompany())
        val sub = "auth0|${uniq()}"
        assertEquals(Login.Ok(older), auth.resolveLogin(sub, email, emailVerified = true))
        assertNull(accountRow(newer).authSubject)
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
