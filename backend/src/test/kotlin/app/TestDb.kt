package app

import io.ktor.server.config.MapApplicationConfig
import org.testcontainers.containers.PostgreSQLContainer

// One shared Postgres container for the whole test run.
object TestDb {
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17").apply { start() }
}

// Test-only session keys. Production keys come from the environment, never from code.
fun testConfig() = MapApplicationConfig(
    "db.url" to TestDb.postgres.jdbcUrl,
    "db.user" to TestDb.postgres.username,
    "db.password" to TestDb.postgres.password,
    "app.publicUrl" to "http://localhost:5173",
    "auth0.domain" to "test.example.com",
    "auth0.clientId" to "test-client",
    "auth0.clientSecret" to "test-secret",
    "session.encryptKey" to "00112233445566778899aabbccddeeff",
    "session.signKey" to "ffeeddccbbaa99887766554433221100",
)
