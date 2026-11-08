package app.integ

import app.insertAccount
import app.insertCompany
import app.routeTest
import app.sessionCookie
import app.uniq
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthIntegTest {

    @Test
    fun logoutRedirectsToAuth0ClearsCookieAndEndsSession() = routeTest {
        val userId = insertAccount("out-${uniq()}@x.pt", companyId = insertCompany())
        val cookie = sessionCookie(userId)
        assertEquals(HttpStatusCode.OK, client.get("/api/me") { header(HttpHeaders.Cookie, cookie) }.status)

        val response = client.post("/auth/logout") { header(HttpHeaders.Cookie, cookie) }

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(
            "https://test.example.com/v2/logout?client_id=test-client&returnTo=http%3A%2F%2Flocalhost%3A5173",
            response.headers[HttpHeaders.Location],
        )
        val setCookie = response.headers[HttpHeaders.SetCookie]!!
        assertTrue(setCookie.startsWith("sid=;"), setCookie)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
    }

    @Test
    fun teamsWithoutSessionGets401() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/teams").status)
    }
}
