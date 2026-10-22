package app.persistence.team

import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class TeamRepository(private val ds: DataSource) {

    fun teamInCompany(teamId: UUID, companyId: UUID): Boolean =
        ds.connection.use { c ->
            c.prepareStatement("SELECT 1 FROM team WHERE id = ? AND company_id = ?").use { st ->
                st.setObject(1, teamId)
                st.setObject(2, companyId)
                st.executeQuery().use { it.next() }
            }
        }

    fun createTeam(companyId: UUID, name: String): UUID {
        val id = UUID.randomUUID()
        ds.connection.use { c ->
            c.prepareStatement("INSERT INTO team (id, company_id, name) VALUES (?, ?, ?)").use { st ->
                st.setObject(1, id)
                st.setObject(2, companyId)
                st.setString(3, name)
                st.executeUpdate()
            }
        }
        return id
    }

    // Returns null on a user_account.email unique violation (23505); rethrows any other SQLException.
    fun createInvitedMember(
        companyId: UUID,
        teamId: UUID,
        email: String,
        displayName: String,
        role: String,
        ftePercent: Int,
        worksShifts: Boolean,
    ): UUID? {
        val userId = UUID.randomUUID()
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(
                    "INSERT INTO user_account (id, company_id, email, auth_subject, display_name) VALUES (?, ?, ?, NULL, ?)"
                ).use { st ->
                    st.setObject(1, userId)
                    st.setObject(2, companyId)
                    st.setString(3, email)
                    st.setString(4, displayName)
                    st.executeUpdate()
                }
                c.prepareStatement(
                    "INSERT INTO team_member (team_id, user_id, role, works_shifts, fte_percent) VALUES (?, ?, ?, ?, ?)"
                ).use { st ->
                    st.setObject(1, teamId)
                    st.setObject(2, userId)
                    st.setString(3, role)
                    st.setBoolean(4, worksShifts)
                    st.setInt(5, ftePercent)
                    st.executeUpdate()
                }
                c.commit()
                return userId
            } catch (e: SQLException) {
                c.rollback()
                // 23505 is the user_account.email unique violation: already taken, or lost a race to a concurrent invite.
                if (e.sqlState == "23505") return null
                throw e
            }
        }
    }
}
