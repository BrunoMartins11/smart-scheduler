package app

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthTest {

    private val ds = connectDatabase(testConfig()).also { migrate(it) }

    // Unique per test run, so tests can share one database.
    private fun uniq() = UUID.randomUUID().toString().take(8)

    private fun insertAccount(email: String, sub: String? = null, deactivated: Boolean = false): UUID {
        val id = UUID.randomUUID()
        ds.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO user_account (id, company_id, email, auth_subject, display_name, deactivated_at) " +
                    "VALUES (?, (SELECT id FROM company LIMIT 1), ?, ?, 'Test', ?)"
            ).use { st ->
                st.setObject(1, id)
                st.setString(2, email)
                st.setString(3, sub)
                if (deactivated) st.setTimestamp(4, java.sql.Timestamp(0)) else st.setNull(4, java.sql.Types.TIMESTAMP)
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
}
