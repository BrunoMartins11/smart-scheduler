package app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = testConfig() }
        application { module() }
        block()
    }

    @Test
    fun healthReturnsOk() = app {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun meWithoutSessionIsUnauthorized() = app {
        val response = client.get("/api/me")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
