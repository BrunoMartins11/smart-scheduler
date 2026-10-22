package app

import app.controller.auth.UserSession
import app.persistence.connectDatabase
import app.persistence.migrate
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.hex
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID

// One pool for the whole test JVM; each pool holds up to 10 Postgres connections.
val testDs: HikariDataSource by lazy { connectDatabase(testConfig()).also { migrate(it) } }

// Unique per test run, so tests can share one database.
fun uniq() = UUID.randomUUID().toString().take(8)

// Accounts created without a company join the first company in the table, so one must exist.
fun ensureDefaultCompany() {
    testDs.connection.use { c ->
        c.createStatement().executeUpdate("INSERT INTO company (name) SELECT 'Acme' WHERE NOT EXISTS (SELECT 1 FROM company)")
    }
}

fun insertCompany(): UUID {
    val id = UUID.randomUUID()
    testDs.connection.use { c ->
        c.prepareStatement("INSERT INTO company (id, name) VALUES (?, ?)").use { st ->
            st.setObject(1, id)
            st.setString(2, "Co-${uniq()}")
            st.executeUpdate()
        }
    }
    return id
}

fun insertTeam(companyId: UUID): UUID {
    val id = UUID.randomUUID()
    testDs.connection.use { c ->
        c.prepareStatement("INSERT INTO team (id, company_id, name) VALUES (?, ?, ?)").use { st ->
            st.setObject(1, id)
            st.setObject(2, companyId)
            st.setString(3, "Team-${uniq()}")
            st.executeUpdate()
        }
    }
    return id
}

fun insertAccount(
    email: String,
    sub: String? = null,
    deactivated: Boolean = false,
    companyId: UUID? = null,
    admin: Boolean = false,
): UUID {
    val id = UUID.randomUUID()
    testDs.connection.use { c ->
        c.prepareStatement(
            "INSERT INTO user_account (id, company_id, email, auth_subject, display_name, deactivated_at, is_company_admin) " +
                "VALUES (?, COALESCE(?, (SELECT id FROM company LIMIT 1)), ?, ?, 'Test', ?, ?)"
        ).use { st ->
            st.setObject(1, id)
            st.setObject(2, companyId)
            st.setString(3, email)
            st.setString(4, sub)
            if (deactivated) st.setTimestamp(5, Timestamp(0)) else st.setNull(5, Types.TIMESTAMP)
            st.setBoolean(6, admin)
            st.executeUpdate()
        }
    }
    return id
}

fun addMember(teamId: UUID, userId: UUID, role: String, active: Boolean = true) {
    testDs.connection.use { c ->
        c.prepareStatement(
            "INSERT INTO team_member (team_id, user_id, role, fte_percent, active) VALUES (?, ?, ?, 100, ?)"
        ).use { st ->
            st.setObject(1, teamId)
            st.setObject(2, userId)
            st.setString(3, role)
            st.setBoolean(4, active)
            st.executeUpdate()
        }
    }
}

fun memberCount(teamId: UUID): Int =
    testDs.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM team_member WHERE team_id = ?").use { st ->
            st.setObject(1, teamId)
            st.executeQuery().use { it.next(); it.getInt(1) }
        }
    }

data class AccountRow(val authSubject: String?, val companyId: UUID, val email: String)

fun accountRow(userId: UUID): AccountRow =
    testDs.connection.use { c ->
        c.prepareStatement("SELECT auth_subject, company_id, email::text FROM user_account WHERE id = ?").use { st ->
            st.setObject(1, userId)
            st.executeQuery().use { rs ->
                rs.next()
                AccountRow(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getString(3))
            }
        }
    }

fun teamCompany(teamId: UUID): UUID? =
    testDs.connection.use { c ->
        c.prepareStatement("SELECT company_id FROM team WHERE id = ?").use { st ->
            st.setObject(1, teamId)
            st.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
        }
    }

// Gets the `sid` cookie from the same Sessions setup the app uses, so the wire format cannot drift.
fun sessionCookie(userId: UUID): String {
    val config = testConfig()
    val encryptKey = hex(config.property("session.encryptKey").getString())
    val signKey = hex(config.property("session.signKey").getString())
    var cookie = ""
    testApplication {
        application {
            install(Sessions) {
                cookie<UserSession>("sid") {
                    transform(SessionTransportTransformerEncrypt(encryptKey, signKey))
                }
            }
            routing {
                get("/") {
                    call.sessions.set(UserSession(userId.toString()))
                    call.respondText("")
                }
            }
        }
        val setCookie = client.get("/").headers[HttpHeaders.SetCookie]!!
        cookie = "sid=" + setCookie.substringAfter("sid=").substringBefore(";")
    }
    return cookie
}

// Route-level harness: the real module() against the shared test Postgres.
fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    environment { config = testConfig() }
    application { module() }
    block()
}
