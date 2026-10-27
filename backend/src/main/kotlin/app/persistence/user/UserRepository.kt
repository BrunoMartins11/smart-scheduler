package app.persistence.user

import app.model.Account
import app.model.CurrentUser
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class UserRepository(private val ds: DataSource) {

    fun findBySubject(sub: String): Account? = findAccount("auth_subject = ?", sub)

    fun findUnclaimedByEmail(email: String): Account? = findAccount("email = ? AND auth_subject IS NULL", email)

    private fun findAccount(where: String, value: String): Account? =
        ds.connection.use { c ->
            c.prepareStatement("SELECT id, deactivated_at IS NOT NULL FROM user_account WHERE $where").use { st ->
                st.setString(1, value)
                st.executeQuery().use { rs ->
                    if (rs.next()) Account(rs.getObject(1, UUID::class.java), rs.getBoolean(2)) else null
                }
            }
        }

    // The IS NULL guard means a concurrent claim of the same invite loses instead of overwriting.
    fun claimInvite(id: UUID, sub: String): Boolean =
        ds.connection.use { c ->
            c.prepareStatement("UPDATE user_account SET auth_subject = ? WHERE id = ? AND auth_subject IS NULL").use { st ->
                st.setString(1, sub)
                st.setObject(2, id)
                st.executeUpdate()
            } == 1
        }

    fun findCurrentUser(id: UUID): CurrentUser? =
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
}
