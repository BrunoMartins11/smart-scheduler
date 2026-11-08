package app.integ

import app.accountRow
import app.callAs
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.memberCount
import app.persistence.user.UserRepository
import app.routeTest
import app.service.auth.AuthService
import app.service.auth.Login
import app.testDs
import app.uniq
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Invite by a team manager over the API, then the invitee's first login claims the account.
class InviteLoginIntegTest {

    private val auth = AuthService(UserRepository(testDs))

    @Test
    fun adminInvitesMemberAsUnclaimedAccountThatCanLogIn() = routeTest {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true)
        val email = "ana-${uniq()}@hospital.pt"

        val response = callAs(admin, "POST", "/api/teams/$team/invites", """{"email":"  $email ","displayName":"Ana","role":"MEMBER","ftePercent":50,"worksShifts":true}""")
        assertEquals(HttpStatusCode.Created, response.status)
        val userId = UUID.fromString(Json.parseToJsonElement(response.bodyAsText()).jsonObject["userId"]!!.jsonPrimitive.content)

        val row = accountRow(userId)
        assertNull(row.authSubject)
        assertEquals(company, row.companyId)
        assertEquals(email, row.email)
        assertEquals(1, memberCount(team))
        assertEquals(Login.Ok(userId), auth.resolveLogin("auth0|${uniq()}", email, emailVerified = true))
    }
}
