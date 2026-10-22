package app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.ApplicationConfig
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import javax.sql.DataSource

fun connectDatabase(config: ApplicationConfig): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.property("db.url").getString()
            username = config.property("db.user").getString()
            password = config.property("db.password").getString()
        }
    )

fun migrate(ds: DataSource): MigrateResult = Flyway.configure().dataSource(ds).load().migrate()
