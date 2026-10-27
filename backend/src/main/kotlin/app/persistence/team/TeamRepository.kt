package app.persistence.team

import app.model.TeamRules
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

data class TeamSummary(val id: UUID, val name: String)

data class TeamMemberRow(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val role: String,
    val ftePercent: Int,
    val worksShifts: Boolean,
    val active: Boolean,
)

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

    // Returns null on a user_account (company_id, email) unique violation (23505): a same-company duplicate or a lost race. Rethrows any other SQLException.
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
                // 23505 is the user_account (company_id, email) unique violation: same-company duplicate or lost race.
                if (e.sqlState == "23505") return null
                throw e
            }
        }
    }

    // Admins see every team in the company; everyone else sees teams where they are an active member.
    fun listVisibleTeams(companyId: UUID, userId: UUID, isCompanyAdmin: Boolean): List<TeamSummary> =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT id, name FROM team WHERE company_id = ? AND (? OR id IN (SELECT team_id FROM team_member WHERE user_id = ? AND active)) ORDER BY name"
            ).use { st ->
                st.setObject(1, companyId)
                st.setBoolean(2, isCompanyAdmin)
                st.setObject(3, userId)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(TeamSummary(rs.getObject(1, UUID::class.java), rs.getString(2))) }
                }
            }
        }

    fun listMembers(teamId: UUID, companyId: UUID): List<TeamMemberRow> =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT u.id, u.email::text, u.display_name, tm.role, tm.fte_percent, tm.works_shifts, tm.active " +
                    "FROM team_member tm JOIN team t ON t.id = tm.team_id JOIN user_account u ON u.id = tm.user_id " +
                    "WHERE t.id = ? AND t.company_id = ? ORDER BY u.display_name"
            ).use { st ->
                st.setObject(1, teamId)
                st.setObject(2, companyId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            TeamMemberRow(
                                rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getBoolean(6), rs.getBoolean(7),
                            )
                        )
                    }
                }
            }
        }

    fun memberRole(teamId: UUID, companyId: UUID, userId: UUID): String? =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT role FROM team_member WHERE team_id = ? AND user_id = ? AND team_id IN (SELECT id FROM team WHERE company_id = ?)"
            ).use { st ->
                st.setObject(1, teamId)
                st.setObject(2, userId)
                st.setObject(3, companyId)
                st.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        }

    // Null arguments keep the stored value (partial update). Returns false when no member row matches.
    fun updateMember(
        teamId: UUID,
        companyId: UUID,
        userId: UUID,
        ftePercent: Int?,
        worksShifts: Boolean?,
        role: String?,
        active: Boolean?,
    ): Boolean =
        ds.connection.use { c ->
            c.prepareStatement(
                "UPDATE team_member SET fte_percent = COALESCE(?, fte_percent), works_shifts = COALESCE(?, works_shifts), " +
                    "role = COALESCE(?, role), active = COALESCE(?, active) " +
                    "WHERE team_id = ? AND user_id = ? AND team_id IN (SELECT id FROM team WHERE company_id = ?)"
            ).use { st ->
                st.setObject(1, ftePercent)
                st.setObject(2, worksShifts)
                st.setObject(3, role)
                st.setObject(4, active)
                st.setObject(5, teamId)
                st.setObject(6, userId)
                st.setObject(7, companyId)
                st.executeUpdate() == 1
            }
        }

    // Soft remove: sets active = false, never deletes the row.
    fun deactivateMember(teamId: UUID, companyId: UUID, userId: UUID): Boolean =
        ds.connection.use { c ->
            c.prepareStatement(
                "UPDATE team_member SET active = false WHERE team_id = ? AND user_id = ? AND team_id IN (SELECT id FROM team WHERE company_id = ?)"
            ).use { st ->
                st.setObject(1, teamId)
                st.setObject(2, userId)
                st.setObject(3, companyId)
                st.executeUpdate() == 1
            }
        }

    // Null when the team is not in the company.
    fun getRules(teamId: UUID, companyId: UUID): TeamRules? =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT min_rest_between_shifts_min, max_consecutive_work_min, max_avg_weekly_work_min, avg_reference_weeks, " +
                    "min_weekly_rest_min, max_consecutive_nights, rest_after_night_run_min FROM team WHERE id = ? AND company_id = ?"
            ).use { st ->
                st.setObject(1, teamId)
                st.setObject(2, companyId)
                st.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    TeamRules(
                        rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6).takeUnless { rs.wasNull() },
                        rs.getInt(7).takeUnless { rs.wasNull() },
                    )
                }
            }
        }

    // Full replace of the seven rule columns. Returns false when the team is not in the company.
    fun updateRules(teamId: UUID, companyId: UUID, rules: TeamRules): Boolean =
        ds.connection.use { c ->
            c.prepareStatement(
                "UPDATE team SET min_rest_between_shifts_min = ?, max_consecutive_work_min = ?, max_avg_weekly_work_min = ?, " +
                    "avg_reference_weeks = ?, min_weekly_rest_min = ?, max_consecutive_nights = ?, rest_after_night_run_min = ? " +
                    "WHERE id = ? AND company_id = ?"
            ).use { st ->
                st.setInt(1, rules.minRestBetweenShiftsMin)
                st.setInt(2, rules.maxConsecutiveWorkMin)
                st.setInt(3, rules.maxAvgWeeklyWorkMin)
                st.setInt(4, rules.avgReferenceWeeks)
                st.setInt(5, rules.minWeeklyRestMin)
                st.setObject(6, rules.maxConsecutiveNights)
                st.setObject(7, rules.restAfterNightRunMin)
                st.setObject(8, teamId)
                st.setObject(9, companyId)
                st.executeUpdate() == 1
            }
        }

    // Single UPDATE ... FROM. Both teams must be in the company. Copies only the seven rule columns.
    fun copyRules(fromTeamId: UUID, toTeamId: UUID, companyId: UUID): Boolean =
        ds.connection.use { c ->
            c.prepareStatement(
                "UPDATE team t SET min_rest_between_shifts_min = s.min_rest_between_shifts_min, " +
                    "max_consecutive_work_min = s.max_consecutive_work_min, max_avg_weekly_work_min = s.max_avg_weekly_work_min, " +
                    "avg_reference_weeks = s.avg_reference_weeks, min_weekly_rest_min = s.min_weekly_rest_min, " +
                    "max_consecutive_nights = s.max_consecutive_nights, rest_after_night_run_min = s.rest_after_night_run_min " +
                    "FROM team s WHERE t.id = ? AND t.company_id = ? AND s.id = ? AND s.company_id = ?"
            ).use { st ->
                st.setObject(1, toTeamId)
                st.setObject(2, companyId)
                st.setObject(3, fromTeamId)
                st.setObject(4, companyId)
                st.executeUpdate() == 1
            }
        }
}
