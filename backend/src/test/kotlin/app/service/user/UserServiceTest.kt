package app.service.user

import app.addMember
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.persistence.user.UserRepository
import app.testDs
import app.uniq
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserServiceTest {

    private val users = UserService(UserRepository(testDs))

    @Test
    fun loadCurrentUserIsNullForUnknownId() {
        assertNull(users.loadCurrentUser(UUID.randomUUID()))
    }

    @Test
    fun loadCurrentUserReportsDeactivatedAccount() {
        val id = insertAccount("gone-${uniq()}@x.pt", companyId = insertCompany(), deactivated = true)
        assertTrue(users.loadCurrentUser(id)!!.deactivated)
    }

    @Test
    fun loadCurrentUserListsOnlyActiveManagerTeamsAndAdminFlag() {
        val company = insertCompany()
        val managed = insertTeam(company)
        val memberOf = insertTeam(company)
        val inactive = insertTeam(company)
        val userId = insertAccount("user-${uniq()}@x.pt", companyId = company, admin = true)
        addMember(managed, userId, "MANAGER")
        addMember(memberOf, userId, "MEMBER")
        addMember(inactive, userId, "MANAGER", active = false)

        val user = users.loadCurrentUser(userId)!!
        assertEquals(setOf(managed), user.managedTeamIds)
        assertTrue(user.isCompanyAdmin)
        assertFalse(user.deactivated)
    }
}
