package app.persistence.availability

import app.model.MemberSlot
import app.model.MyAvailabilityPeriod
import app.model.SchedulePeriod
import app.model.ShiftMember
import app.model.SubmissionRow
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

// Every statement reaches the period or team through team.company_id, so another company's rows match nothing.
class AvailabilityRepository(private val ds: DataSource) {

    // Only shift members (active, works_shifts) see their own periods; DRAFT is never listed.
    fun listMine(userId: UUID, companyId: UUID): List<MyAvailabilityPeriod> =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT t.id, t.name, p.id, p.start_date, p.end_date, p.status, p.input_deadline, " +
                    "EXISTS (SELECT 1 FROM availability a JOIN shift_slot s ON s.id = a.slot_id WHERE s.period_id = p.id AND a.team_member_id = tm.id) " +
                    "FROM team_member tm JOIN team t ON t.id = tm.team_id JOIN schedule_period p ON p.team_id = t.id " +
                    "WHERE tm.user_id = ? AND tm.active AND tm.works_shifts AND t.company_id = ? " +
                    "AND p.status IN ('OPEN', 'CLOSED', 'PUBLISHED') ORDER BY p.start_date DESC"
            ).use { st ->
                st.setObject(1, userId)
                st.setObject(2, companyId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            MyAvailabilityPeriod(
                                rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java),
                                rs.getObject(4, LocalDate::class.java), rs.getObject(5, LocalDate::class.java),
                                rs.getString(6), rs.getObject(7, OffsetDateTime::class.java)?.toInstant(), rs.getBoolean(8),
                            )
                        )
                    }
                }
            }
        }

    fun shiftMember(teamId: UUID, companyId: UUID, userId: UUID): ShiftMember? =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT tm.id, u.display_name FROM team_member tm JOIN team t ON t.id = tm.team_id JOIN user_account u ON u.id = tm.user_id " +
                    "WHERE tm.team_id = ? AND tm.user_id = ? AND t.company_id = ? AND tm.active AND tm.works_shifts"
            ).use { st ->
                st.setObject(1, teamId)
                st.setObject(2, userId)
                st.setObject(3, companyId)
                st.executeQuery().use { rs -> if (rs.next()) ShiftMember(rs.getObject(1, UUID::class.java), rs.getString(2)) else null }
            }
        }

    fun period(teamId: UUID, companyId: UUID, periodId: UUID): SchedulePeriod? =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT p.id, p.start_date, p.end_date, p.status, p.input_deadline FROM schedule_period p JOIN team t ON t.id = p.team_id " +
                    "WHERE p.id = ? AND p.team_id = ? AND t.company_id = ?"
            ).use { st ->
                st.setObject(1, periodId)
                st.setObject(2, teamId)
                st.setObject(3, companyId)
                st.executeQuery().use { rs ->
                    if (rs.next()) SchedulePeriod(
                        rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java),
                        rs.getObject(3, LocalDate::class.java), rs.getString(4),
                        rs.getObject(5, OffsetDateTime::class.java)?.toInstant(),
                    ) else null
                }
            }
        }

    // Every slot of the period, marked or not. enteredByManager is set when the mark's entered_by is someone other than the member.
    fun slots(periodId: UUID, teamId: UUID, companyId: UUID, teamMemberId: UUID, userId: UUID): List<MemberSlot> =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT s.id, s.date, st.name, st.start_time, st.end_time, st.is_night, a.kind, " +
                    "COALESCE(a.entered_by <> ?, false) " +
                    "FROM shift_slot s JOIN schedule_period p ON p.id = s.period_id JOIN team t ON t.id = p.team_id " +
                    "JOIN shift_template st ON st.id = s.template_id " +
                    "LEFT JOIN availability a ON a.slot_id = s.id AND a.team_member_id = ? " +
                    "WHERE s.period_id = ? AND p.team_id = ? AND t.company_id = ? ORDER BY s.date, st.start_time, st.name"
            ).use { st ->
                st.setObject(1, userId)
                st.setObject(2, teamMemberId)
                st.setObject(3, periodId)
                st.setObject(4, teamId)
                st.setObject(5, companyId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            MemberSlot(
                                rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java), rs.getString(3),
                                rs.getObject(4, LocalTime::class.java), rs.getObject(5, LocalTime::class.java),
                                rs.getBoolean(6), rs.getString(7), rs.getBoolean(8),
                            )
                        )
                    }
                }
            }
        }

    // NOT_SUBMITTED: no rows in the period. MANAGER: any row was entered by someone other than the member. Else MEMBER.
    fun submissions(periodId: UUID, teamId: UUID, companyId: UUID): List<SubmissionRow> =
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT u.id, u.display_name, CASE " +
                    "WHEN NOT EXISTS (SELECT 1 FROM availability a JOIN shift_slot s ON s.id = a.slot_id WHERE s.period_id = ? AND a.team_member_id = tm.id) THEN 'NOT_SUBMITTED' " +
                    "WHEN EXISTS (SELECT 1 FROM availability a JOIN shift_slot s ON s.id = a.slot_id WHERE s.period_id = ? AND a.team_member_id = tm.id AND a.entered_by <> tm.user_id) THEN 'MANAGER' " +
                    "ELSE 'MEMBER' END " +
                    "FROM team_member tm JOIN team t ON t.id = tm.team_id JOIN user_account u ON u.id = tm.user_id " +
                    "WHERE tm.team_id = ? AND t.company_id = ? AND tm.active AND tm.works_shifts ORDER BY u.display_name"
            ).use { st ->
                st.setObject(1, periodId)
                st.setObject(2, periodId)
                st.setObject(3, teamId)
                st.setObject(4, companyId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(SubmissionRow(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)))
                    }
                }
            }
        }

    // One transaction. The period is locked FOR SHARE so a close (FOR UPDATE) serialises with it, and the member's
    // team_member row FOR UPDATE. Null when the period or the shift member is not in the team and company.
    fun <T> lockForMarks(teamId: UUID, companyId: UUID, periodId: UUID, userId: UUID, block: MarkTx.() -> T): T? =
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                val status = c.prepareStatement(
                    "SELECT p.status FROM schedule_period p JOIN team t ON t.id = p.team_id " +
                        "WHERE p.id = ? AND p.team_id = ? AND t.company_id = ? FOR SHARE OF p"
                ).use { st ->
                    st.setObject(1, periodId)
                    st.setObject(2, teamId)
                    st.setObject(3, companyId)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
                val memberId = status?.let {
                    c.prepareStatement(
                        "SELECT tm.id FROM team_member tm JOIN team t ON t.id = tm.team_id " +
                            "WHERE tm.team_id = ? AND tm.user_id = ? AND t.company_id = ? AND tm.active AND tm.works_shifts FOR UPDATE OF tm"
                    ).use { st ->
                        st.setObject(1, teamId)
                        st.setObject(2, userId)
                        st.setObject(3, companyId)
                        st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                    }
                }
                val result = if (status != null && memberId != null) {
                    MarkTx(c, periodId, teamId, companyId, memberId, status).block()
                } else {
                    null
                }
                c.commit()
                result
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
}

// Only reachable inside AvailabilityRepository.lockForMarks, so every write runs under the locks.
class MarkTx internal constructor(
    private val c: Connection,
    private val periodId: UUID,
    private val teamId: UUID,
    private val companyId: UUID,
    val teamMemberId: UUID,
    val status: String,
) {
    // True only when every id is a slot of this period in this company.
    fun slotsInPeriod(slotIds: List<UUID>): Boolean =
        c.prepareStatement(
            "SELECT count(*) FROM shift_slot s JOIN schedule_period p ON p.id = s.period_id JOIN team t ON t.id = p.team_id " +
                "WHERE s.id = ANY(?) AND s.period_id = ? AND t.company_id = ?"
        ).use { st ->
            st.setArray(1, c.createArrayOf("uuid", slotIds.map { it.toString() }.toTypedArray()))
            st.setObject(2, periodId)
            st.setObject(3, companyId)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) == slotIds.size }
        }

    // Insert or overwrite the mark. entered_by is the acting user and created_at is refreshed on every change.
    fun set(slotId: UUID, kind: String, actorId: UUID) {
        c.prepareStatement(
            "INSERT INTO availability (slot_id, team_member_id, kind, entered_by, created_at) " +
                "SELECT s.id, ?, ?, ?, now() FROM shift_slot s JOIN schedule_period p ON p.id = s.period_id JOIN team t ON t.id = p.team_id " +
                "WHERE s.id = ? AND p.id = ? AND t.company_id = ? " +
                "ON CONFLICT (slot_id, team_member_id) DO UPDATE SET kind = EXCLUDED.kind, entered_by = EXCLUDED.entered_by, created_at = EXCLUDED.created_at"
        ).use { st ->
            st.setObject(1, teamMemberId)
            st.setString(2, kind)
            st.setObject(3, actorId)
            st.setObject(4, slotId)
            st.setObject(5, periodId)
            st.setObject(6, companyId)
            st.executeUpdate()
        }
    }

    fun clear(slotId: UUID) {
        c.prepareStatement(
            "DELETE FROM availability a USING shift_slot s, schedule_period p, team t " +
                "WHERE a.slot_id = ? AND a.team_member_id = ? AND s.id = a.slot_id AND p.id = s.period_id AND t.id = p.team_id AND t.company_id = ?"
        ).use { st ->
            st.setObject(1, slotId)
            st.setObject(2, teamMemberId)
            st.setObject(3, companyId)
            st.executeUpdate()
        }
    }

    fun audit(actorId: UUID, payload: String) {
        c.prepareStatement(
            "INSERT INTO audit_event (company_id, team_id, actor_user_id, entity_type, entity_id, action, payload) " +
                "SELECT t.company_id, t.id, ?, 'availability', ?, 'SET_ON_BEHALF', CAST(? AS jsonb) FROM team t WHERE t.id = ? AND t.company_id = ?"
        ).use { st ->
            st.setObject(1, actorId)
            st.setString(2, teamMemberId.toString())
            st.setString(3, payload)
            st.setObject(4, teamId)
            st.setObject(5, companyId)
            st.executeUpdate()
        }
    }
}
