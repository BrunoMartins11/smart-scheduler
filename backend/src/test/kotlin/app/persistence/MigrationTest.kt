package app.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationTest {

    @Test
    fun migrationsApplyAndAuditEventIsAppendOnly() {
        // Fresh database, so every migration runs (V1 and V2).
        val pg = PostgreSQLContainer("postgres:17").apply { start() }
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = pg.jdbcUrl
            username = pg.username
            password = pg.password
        }).use { ds ->
            assertEquals(2, migrate(ds).migrationsExecuted)
            ds.connection.use { c ->
                c.createStatement().executeUpdate("INSERT INTO company (name) VALUES ('Acme')")
                c.createStatement().executeUpdate(
                    "INSERT INTO audit_event (company_id, entity_type, entity_id, action) " +
                        "SELECT id, 'company', id::text, 'created' FROM company"
                )
                assertFailsWith<SQLException> {
                    c.createStatement().executeUpdate("UPDATE audit_event SET action = 'changed'")
                }
                assertFailsWith<SQLException> {
                    c.createStatement().executeUpdate("TRUNCATE audit_event")
                }
            }
        }
        pg.stop()
    }
}
