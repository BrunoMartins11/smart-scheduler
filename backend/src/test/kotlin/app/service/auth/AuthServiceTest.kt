package app.service.auth

import app.insertAccount
import app.persistence.user.UserRepository
import app.service.user.UserService
import app.testDs
import app.uniq
import app.ensureDefaultCompany
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthServiceTest {

    private val users = UserService(UserRepository(testDs))
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
}
