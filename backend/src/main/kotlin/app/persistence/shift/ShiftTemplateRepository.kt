package app.persistence.shift

import app.model.ShiftTemplate
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalTime
import java.util.UUID
import javax.sql.DataSource

// Every statement reaches the template through team.company_id, so a team outside the company matches nothing.
class ShiftTemplateRepository(private val ds: DataSource) {

    fun list(teamId: UUID, companyId: UUID): List<ShiftTemplate> =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT st.id, st.name, st.start_time, st.end_time, st.is_night, st.default_min_staff, st.default_max_staff, st.active " +
                    "FROM shift_template st JOIN team t ON t.id = st.team_id WHERE st.team_id = ? AND t.company_id = ? " +
                    "ORDER BY st.start_time, st.name"
            ).use { st ->
                st.setObject(1, teamId)
                st.setObject(2, companyId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            ShiftTemplate(
                                rs.getObject(1, UUID::class.java), rs.getString(2),
                                rs.getObject(3, LocalTime::class.java), rs.getObject(4, LocalTime::class.java),
                                rs.getBoolean(5), rs.getInt(6), rs.getInt(7), rs.getBoolean(8),
                            )
                        )
                    }
                }
            }
        }

    fun create(id: UUID, teamId: UUID, companyId: UUID, t: ShiftTemplate): Boolean =
        ds.connection.use { c -> insertInto(c, id, teamId, companyId, t) }

    fun setActive(templateId: UUID, teamId: UUID, companyId: UUID, active: Boolean): Boolean =
        ds.connection.use { c -> setActiveIn(c, templateId, teamId, companyId, active) }

    // One transaction. FOR UPDATE waits out an in-flight slot insert (its FK holds a lock on this row). The used
    // check is a separate statement, so it takes a fresh snapshot and sees that insert once committed.
    // Unused: updated in place, returns templateId. Used: the old row is deactivated and a new row (newId)
    // takes the values, keeping the old active flag. Null when not in reach.
    fun edit(templateId: UUID, teamId: UUID, companyId: UUID, t: ShiftTemplate, newId: UUID): UUID? {
        return ds.connection.use { c ->
            c.autoCommit = false
            try {
                val active = c.prepareStatement(
                    "SELECT st.active FROM shift_template st JOIN team tm ON tm.id = st.team_id " +
                        "WHERE st.id = ? AND st.team_id = ? AND tm.company_id = ? FOR UPDATE OF st"
                ).use { st ->
                    st.setObject(1, templateId)
                    st.setObject(2, teamId)
                    st.setObject(3, companyId)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else null }
                }
                if (active == null) {
                    c.rollback()
                    return null
                }
                val used = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM shift_slot WHERE template_id = ?)").use { st ->
                    st.setObject(1, templateId)
                    st.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
                }
                val id = if (used) {
                    setActiveIn(c, templateId, teamId, companyId, false)
                    insertInto(c, newId, teamId, companyId, t.copy(active = active))
                    newId
                } else {
                    updateIn(c, templateId, teamId, companyId, t)
                    templateId
                }
                c.commit()
                id
            } catch (e: SQLException) {
                c.rollback()
                throw e
            }
        }
    }

    private fun insertInto(c: Connection, id: UUID, teamId: UUID, companyId: UUID, t: ShiftTemplate): Boolean =
        c.prepareStatement(
            "INSERT INTO shift_template (id, team_id, name, start_time, end_time, is_night, default_min_staff, default_max_staff, active) " +
                "SELECT ?, t.id, ?, ?, ?, ?, ?, ?, ? FROM team t WHERE t.id = ? AND t.company_id = ?"
        ).use { st ->
            st.setObject(1, id)
            st.setString(2, t.name)
            st.setObject(3, t.startTime)
            st.setObject(4, t.endTime)
            st.setBoolean(5, t.isNight)
            st.setInt(6, t.minStaff)
            st.setInt(7, t.maxStaff)
            st.setBoolean(8, t.active)
            st.setObject(9, teamId)
            st.setObject(10, companyId)
            st.executeUpdate() == 1
        }

    // Leaves active alone: an edit never changes whether a template is active.
    private fun updateIn(c: Connection, id: UUID, teamId: UUID, companyId: UUID, t: ShiftTemplate): Boolean =
        c.prepareStatement(
            "UPDATE shift_template st SET name = ?, start_time = ?, end_time = ?, is_night = ?, default_min_staff = ?, default_max_staff = ? " +
                "FROM team t WHERE st.id = ? AND st.team_id = ? AND t.id = st.team_id AND t.company_id = ?"
        ).use { st ->
            st.setString(1, t.name)
            st.setObject(2, t.startTime)
            st.setObject(3, t.endTime)
            st.setBoolean(4, t.isNight)
            st.setInt(5, t.minStaff)
            st.setInt(6, t.maxStaff)
            st.setObject(7, id)
            st.setObject(8, teamId)
            st.setObject(9, companyId)
            st.executeUpdate() == 1
        }

    private fun setActiveIn(c: Connection, id: UUID, teamId: UUID, companyId: UUID, active: Boolean): Boolean =
        c.prepareStatement(
            "UPDATE shift_template st SET active = ? FROM team t " +
                "WHERE st.id = ? AND st.team_id = ? AND t.id = st.team_id AND t.company_id = ?"
        ).use { st ->
            st.setBoolean(1, active)
            st.setObject(2, id)
            st.setObject(3, teamId)
            st.setObject(4, companyId)
            st.executeUpdate() == 1
        }
}
