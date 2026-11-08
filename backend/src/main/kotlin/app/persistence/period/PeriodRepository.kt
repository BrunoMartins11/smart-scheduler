package app.persistence.period

import app.model.PeriodSlot
import app.model.SchedulePeriod
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

// Every statement reaches the period through team.company_id, so a team outside the company matches nothing.
class PeriodRepository(private val ds: DataSource) {

    enum class CreateOutcome { CREATED, NOT_FOUND, OVERLAP }

    fun list(teamId: UUID, companyId: UUID): List<SchedulePeriod> =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT p.id, p.start_date, p.end_date, p.status, p.input_deadline FROM schedule_period p JOIN team t ON t.id = p.team_id " +
                    "WHERE p.team_id = ? AND t.company_id = ? ORDER BY p.start_date DESC"
            ).use { st ->
                st.setObject(1, teamId)
                st.setObject(2, companyId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            SchedulePeriod(
                                rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java),
                                rs.getObject(3, LocalDate::class.java), rs.getString(4),
                                rs.getObject(5, OffsetDateTime::class.java)?.toInstant(),
                            )
                        )
                    }
                }
            }
        }

    // Null when the period is not in the team and company, so the route answers 404.
    fun slots(periodId: UUID, teamId: UUID, companyId: UUID): List<PeriodSlot>? =
        ds.connection.use { c ->
            val exists = c.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM schedule_period p JOIN team t ON t.id = p.team_id WHERE p.id = ? AND p.team_id = ? AND t.company_id = ?)"
            ).use { st ->
                st.setObject(1, periodId)
                st.setObject(2, teamId)
                st.setObject(3, companyId)
                st.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
            }
            if (!exists) return@use null
            c.prepareStatement(
                "SELECT s.id, s.date, s.template_id, st.name, st.start_time, st.end_time, st.is_night, s.min_staff, s.max_staff, st.default_min_staff, st.default_max_staff " +
                    "FROM shift_slot s JOIN schedule_period p ON p.id = s.period_id JOIN team t ON t.id = p.team_id " +
                    "JOIN shift_template st ON st.id = s.template_id " +
                    "WHERE s.period_id = ? AND p.team_id = ? AND t.company_id = ? ORDER BY s.date, st.start_time, st.name"
            ).use { st ->
                st.setObject(1, periodId)
                st.setObject(2, teamId)
                st.setObject(3, companyId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            PeriodSlot(
                                rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java),
                                rs.getObject(3, UUID::class.java), rs.getString(4),
                                rs.getObject(5, LocalTime::class.java), rs.getObject(6, LocalTime::class.java),
                                rs.getBoolean(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11),
                            )
                        )
                    }
                }
            }
        }

    // One transaction, so a failed slot insert also drops the period. The exclusion constraint raises 23P01 on overlap.
    fun create(periodId: UUID, teamId: UUID, companyId: UUID, start: LocalDate, end: LocalDate): CreateOutcome =
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                val outcome = insertPeriodAndSlots(c, periodId, teamId, companyId, start, end)
                if (outcome == CreateOutcome.CREATED) c.commit() else c.rollback()
                outcome
            } catch (e: SQLException) {
                c.rollback()
                if (e.sqlState == "23P01") CreateOutcome.OVERLAP else throw e
            }
        }

    // Runs block with the period row locked FOR UPDATE in one transaction, so concurrent writers queue on it. Null when not in reach.
    fun <T> locked(periodId: UUID, teamId: UUID, companyId: UUID, block: LockedPeriod.() -> T): T? =
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                val row = c.prepareStatement(
                    "SELECT p.status, p.input_deadline FROM schedule_period p JOIN team t ON t.id = p.team_id " +
                        "WHERE p.id = ? AND p.team_id = ? AND t.company_id = ? FOR UPDATE OF p"
                ).use { st ->
                    st.setObject(1, periodId)
                    st.setObject(2, teamId)
                    st.setObject(3, companyId)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) to rs.getObject(2, OffsetDateTime::class.java)?.toInstant() else null
                    }
                }
                val result = row?.let { (status, deadline) ->
                    LockedPeriod(c, periodId, teamId, companyId, status, deadline).block()
                }
                c.commit()
                result
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    private fun insertPeriodAndSlots(
        c: Connection, periodId: UUID, teamId: UUID, companyId: UUID, start: LocalDate, end: LocalDate,
    ): CreateOutcome {
        val inserted = c.prepareStatement(
            "INSERT INTO schedule_period (id, team_id, start_date, end_date) SELECT ?, t.id, ?, ? FROM team t WHERE t.id = ? AND t.company_id = ?"
        ).use { st ->
            st.setObject(1, periodId)
            st.setObject(2, start)
            st.setObject(3, end)
            st.setObject(4, teamId)
            st.setObject(5, companyId)
            st.executeUpdate() == 1
        }
        if (!inserted) return CreateOutcome.NOT_FOUND
        // One row per date and active template, copying the template's default coverage. Zero templates insert nothing.
        c.prepareStatement(
            "INSERT INTO shift_slot (period_id, template_id, date, min_staff, max_staff) " +
                "SELECT ?, st.id, CAST(d.dt AS date), st.default_min_staff, st.default_max_staff " +
                "FROM shift_template st JOIN team t ON t.id = st.team_id " +
                "CROSS JOIN generate_series(CAST(? AS date)::timestamp, CAST(? AS date)::timestamp, interval '1 day') AS d(dt) " +
                "WHERE st.team_id = ? AND st.active AND t.company_id = ?"
        ).use { st ->
            st.setObject(1, periodId)
            st.setObject(2, start)
            st.setObject(3, end)
            st.setObject(4, teamId)
            st.setObject(5, companyId)
            st.executeUpdate()
        }
        return CreateOutcome.CREATED
    }
}

// Only reachable inside PeriodRepository.locked, so every write runs under the period's row lock.
class LockedPeriod internal constructor(
    private val c: Connection,
    private val periodId: UUID,
    private val teamId: UUID,
    private val companyId: UUID,
    val status: String,
    val inputDeadline: Instant?,
) {
    fun updateStatus(to: String, deadline: Instant?) {
        c.prepareStatement(
            "UPDATE schedule_period p SET status = ?, input_deadline = ?, version = p.version + 1 FROM team t " +
                "WHERE p.id = ? AND t.id = p.team_id AND t.company_id = ?"
        ).use { st ->
            st.setString(1, to)
            st.setObject(2, deadline?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) }, Types.TIMESTAMP_WITH_TIMEZONE)
            st.setObject(3, periodId)
            st.setObject(4, companyId)
            st.executeUpdate()
        }
    }

    fun hasSlot(slotId: UUID): Boolean =
        c.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM shift_slot s JOIN schedule_period p ON p.id = s.period_id JOIN team t ON t.id = p.team_id " +
                "WHERE s.id = ? AND s.period_id = ? AND t.company_id = ?)"
        ).use { st ->
            st.setObject(1, slotId)
            st.setObject(2, periodId)
            st.setObject(3, companyId)
            st.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
        }

    fun setSlotStaff(slotId: UUID, minStaff: Int, maxStaff: Int) {
        c.prepareStatement(
            "UPDATE shift_slot s SET min_staff = ?, max_staff = ? FROM schedule_period p, team t " +
                "WHERE s.id = ? AND p.id = s.period_id AND t.id = p.team_id AND p.id = ? AND t.company_id = ?"
        ).use { st ->
            st.setInt(1, minStaff)
            st.setInt(2, maxStaff)
            st.setObject(3, slotId)
            st.setObject(4, periodId)
            st.setObject(5, companyId)
            st.executeUpdate()
        }
    }

    // Slots go first: they reference the period.
    fun deleteWithSlots() {
        c.prepareStatement(
            "DELETE FROM shift_slot s USING schedule_period p, team t " +
                "WHERE s.period_id = ? AND p.id = s.period_id AND t.id = p.team_id AND t.company_id = ?"
        ).use { st ->
            st.setObject(1, periodId)
            st.setObject(2, companyId)
            st.executeUpdate()
        }
        c.prepareStatement(
            "DELETE FROM schedule_period p USING team t WHERE p.id = ? AND t.id = p.team_id AND t.company_id = ?"
        ).use { st ->
            st.setObject(1, periodId)
            st.setObject(2, companyId)
            st.executeUpdate()
        }
    }

    fun audit(actorId: UUID, action: String, payload: String) {
        c.prepareStatement(
            "INSERT INTO audit_event (company_id, team_id, actor_user_id, entity_type, entity_id, action, payload) " +
                "SELECT t.company_id, t.id, ?, 'schedule_period', ?, ?, CAST(? AS jsonb) FROM team t WHERE t.id = ? AND t.company_id = ?"
        ).use { st ->
            st.setObject(1, actorId)
            st.setString(2, periodId.toString())
            st.setString(3, action)
            st.setString(4, payload)
            st.setObject(5, teamId)
            st.setObject(6, companyId)
            st.executeUpdate()
        }
    }
}
