package app

import app.persistence.team.TeamRepository
import app.persistence.user.UserRepository
import app.service.auth.AuthService
import app.service.auth.Login
import app.service.team.InviteResult
import app.service.team.TeamService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// Invite by a team manager, then the invitee's first login claims the account.
class InviteLoginIntegrationTest {

    private val auth = AuthService(UserRepository(testDs))
    private val teams = TeamService(TeamRepository(testDs))

    @Test
    fun adminInvitesMemberAsUnclaimedAccountThatCanLogIn() {
        val company = insertCompany()
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val team = insertTeam(company)
        val email = "ana-${uniq()}@hospital.pt"

        val userId = assertIs<InviteResult.Created>(
            teams.inviteMember(admin, team, "  $email ", "Ana", "MEMBER", ftePercent = 50, worksShifts = true)
        ).userId

        val row = accountRow(userId)
        assertNull(row.authSubject)
        assertEquals(company, row.companyId)
        assertEquals(email, row.email)
        assertEquals(1, memberCount(team))
        assertEquals(Login.Ok(userId), auth.resolveLogin("auth0|${uniq()}", email, emailVerified = true))
    }
}
