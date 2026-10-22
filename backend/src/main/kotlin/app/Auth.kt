package app

import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import java.security.interfaces.RSAPublicKey
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.Serializable

// The session cookie holds only the user_account id (DATA_MODEL.md §3, Auth0 login flow).
@Serializable
data class UserSession(val userId: String)

data class CurrentUser(
    val id: UUID,
    val companyId: UUID,
    val email: String,
    val displayName: String,
    val deactivated: Boolean,
    val isCompanyAdmin: Boolean,
    // team_ids where this user is an active MANAGER member.
    val managedTeamIds: Set<UUID>,
)

// Callers must answer 404, not 403, when false, so a team outside reach looks like a missing one.
fun CurrentUser.canManage(ds: DataSource, teamId: UUID): Boolean =
    (isCompanyAdmin || teamId in managedTeamIds) && teamInCompany(ds, teamId, companyId)

sealed interface Login {
    data class Ok(val userId: UUID) : Login
    data object NoInvite : Login
    data object Deactivated : Login
}

private data class Account(val id: UUID, val deactivated: Boolean) {
    fun login(): Login = if (deactivated) Login.Deactivated else Login.Ok(id)
}

// Matches an account by Auth0 sub; otherwise claims an unclaimed invite by verified email.
fun resolveLogin(ds: DataSource, sub: String, email: String?, emailVerified: Boolean): Login =
    ds.connection.use { c ->
        val bySub = findAccount(c, "auth_subject = ?", sub)
        val invited = if (bySub == null && email != null && emailVerified) {
            findAccount(c, "email = ? AND auth_subject IS NULL", email)
        } else {
            null
        }
        when {
            bySub != null -> bySub.login()
            invited == null -> Login.NoInvite
            invited.deactivated -> Login.Deactivated
            else -> claimInvite(c, invited.id, sub)
        }
    }

private fun findAccount(c: Connection, where: String, value: String): Account? =
    c.prepareStatement("SELECT id, deactivated_at IS NOT NULL FROM user_account WHERE $where").use { st ->
        st.setString(1, value)
        st.executeQuery().use { rs ->
            if (rs.next()) Account(rs.getObject(1, UUID::class.java), rs.getBoolean(2)) else null
        }
    }

// The IS NULL guard means a concurrent claim of the same invite loses instead of overwriting.
private fun claimInvite(c: Connection, id: UUID, sub: String): Login {
    val updated = c.prepareStatement("UPDATE user_account SET auth_subject = ? WHERE id = ? AND auth_subject IS NULL").use { st ->
        st.setString(1, sub)
        st.setObject(2, id)
        st.executeUpdate()
    }
    return if (updated == 1) Login.Ok(id) else Login.NoInvite
}

fun loadCurrentUser(ds: DataSource, id: UUID): CurrentUser? =
    ds.connection.use { c ->
        val user = c.prepareStatement(
            "SELECT company_id, email, display_name, deactivated_at IS NOT NULL, is_company_admin FROM user_account WHERE id = ?"
        ).use { st ->
            st.setObject(1, id)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    CurrentUser(
                        id, rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getBoolean(5), emptySet(),
                    )
                } else {
                    null
                }
            }
        } ?: return@use null
        user.copy(managedTeamIds = managedTeamIds(c, id))
    }

private fun managedTeamIds(c: Connection, userId: UUID): Set<UUID> =
    c.prepareStatement("SELECT team_id FROM team_member WHERE user_id = ? AND role = 'MANAGER' AND active").use { st ->
        st.setObject(1, userId)
        st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
    }

private fun teamInCompany(ds: DataSource, teamId: UUID, companyId: UUID): Boolean =
    ds.connection.use { c ->
        c.prepareStatement("SELECT 1 FROM team WHERE id = ? AND company_id = ?").use { st ->
            st.setObject(1, teamId)
            st.setObject(2, companyId)
            st.executeQuery().use { it.next() }
        }
    }

// Shared by every protected route. Responds 401 and clears the session when there is no usable user.
suspend fun ApplicationCall.currentUser(ds: DataSource): CurrentUser? {
    val user = sessions.get<UserSession>()?.let { loadCurrentUser(ds, UUID.fromString(it.userId)) }
    if (user == null || user.deactivated) {
        sessions.clear<UserSession>()
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return user
}

// Checks the signature (JWKS), issuer, audience and expiry of the ID token from the token endpoint.
fun verifyIdToken(idToken: String, jwks: JwkProvider, issuer: String, clientId: String): DecodedJWT {
    val key = jwks.get(JWT.decode(idToken).keyId).publicKey as RSAPublicKey
    return JWT.require(Algorithm.RSA256(key, null))
        .withIssuer(issuer)
        .withAudience(clientId)
        .build()
        .verify(idToken)
}

// Binds the OAuth callback to the browser that started the login (login CSRF). Constant-time compare.
fun stateMatches(expected: String?, actual: String?): Boolean =
    expected != null && actual != null &&
        java.security.MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
