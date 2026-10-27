package app.service.team

import app.addMember
import app.insertAccount
import app.insertCompany
import app.insertTeam
import app.memberCount
import app.persistence.team.TeamRepository
import app.persistence.user.UserRepository
import app.service.auth.AuthService
import app.teamCompany
import app.testDs
import app.uniq
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamServiceTest {

    private val auth = AuthService(UserRepository(testDs))
    private val teams = TeamService(TeamRepository(testDs))

    // canManage

    @Test
    fun adminCanManageTeamInOwnCompany() {
        val company = insertCompany()
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        assertTrue(teams.canManage(admin, insertTeam(company)))
    }

    @Test
    fun adminCannotManageTeamOfAnotherCompany() {
        val companyA = insertCompany()
        val companyB = insertCompany()
        val adminA = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = companyA, admin = true))!!
        assertFalse(teams.canManage(adminA, insertTeam(companyB)))
    }

    @Test
    fun adminCannotManageMissingTeam() {
        val company = insertCompany()
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        assertFalse(teams.canManage(admin, UUID.randomUUID()))
    }

    @Test
    fun managerCanManageOnlyOwnTeam() {
        val company = insertCompany()
        val own = insertTeam(company)
        val other = insertTeam(company)
        val userId = insertAccount("manager-${uniq()}@x.pt", companyId = company)
        addMember(own, userId, "MANAGER")
        addMember(other, insertAccount("manager-${uniq()}@x.pt", companyId = company), "MANAGER")
        val manager = auth.currentUser(userId)!!
        assertEquals(setOf(own), manager.managedTeamIds)
        assertTrue(teams.canManage(manager, own))
        assertFalse(teams.canManage(manager, other))
    }

    @Test
    fun memberCannotManage() {
        val company = insertCompany()
        val team = insertTeam(company)
        val userId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, userId, "MEMBER")
        val member = auth.currentUser(userId)!!
        assertEquals(emptySet(), member.managedTeamIds)
        assertFalse(teams.canManage(member, team))
    }

    @Test
    fun inactiveManagerMembershipDoesNotCount() {
        val company = insertCompany()
        val team = insertTeam(company)
        val userId = insertAccount("manager-${uniq()}@x.pt", companyId = company)
        addMember(team, userId, "MANAGER", active = false)
        assertFalse(teams.canManage(auth.currentUser(userId)!!, team))
    }

    // inviteMember

    @Test
    fun managerInvitesMemberToOwnTeam() {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val manager = auth.currentUser(managerId)!!

        assertIs<InviteResult.Created>(
            teams.inviteMember(manager, team, "new-${uniq()}@x.pt", "Bia", "MEMBER")
        )
        assertEquals(2, memberCount(team))
    }

    @Test
    fun managerCannotInviteToOtherTeam() {
        val company = insertCompany()
        val own = insertTeam(company)
        val other = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(own, managerId, "MANAGER")
        val manager = auth.currentUser(managerId)!!

        assertEquals(InviteResult.NotFound, teams.inviteMember(manager, other, "new-${uniq()}@x.pt", "Bia", "MEMBER"))
        assertEquals(0, memberCount(other))
    }

    @Test
    fun nonAdminManagerCannotInviteManager() {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val manager = auth.currentUser(managerId)!!

        assertEquals(InviteResult.NotFound, teams.inviteMember(manager, team, "new-${uniq()}@x.pt", "Bia", "MANAGER"))
        assertEquals(1, memberCount(team))
    }

    @Test
    fun adminOfOtherCompanyGetsNotFound() {
        val companyA = insertCompany()
        val companyB = insertCompany()
        val adminA = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = companyA, admin = true))!!
        val teamB = insertTeam(companyB)

        assertEquals(InviteResult.NotFound, teams.inviteMember(adminA, teamB, "new-${uniq()}@x.pt", "Bia", "MEMBER"))
        assertEquals(0, memberCount(teamB))
    }

    @Test
    fun duplicateEmailIsTakenAndAddsNoMembership() {
        val company = insertCompany()
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val team = insertTeam(company)
        val email = "dup-${uniq()}@x.pt"
        insertAccount(email, companyId = company)

        assertEquals(InviteResult.EmailTaken, teams.inviteMember(admin, team, email.uppercase(), "Dup", "MEMBER"))
        assertEquals(0, memberCount(team))
    }

    @Test
    fun invalidInputIsRejected() {
        val company = insertCompany()
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val team = insertTeam(company)
        val email = "ok-${uniq()}@x.pt"

        assertEquals(InviteResult.Invalid, teams.inviteMember(admin, team, email, "A", "ADMIN"))
        assertEquals(InviteResult.Invalid, teams.inviteMember(admin, team, email, "A", "MEMBER", ftePercent = 0))
        assertEquals(InviteResult.Invalid, teams.inviteMember(admin, team, "nope", "A", "MEMBER"))
        assertEquals(InviteResult.Invalid, teams.inviteMember(admin, team, email, "  ", "MEMBER"))
        assertEquals(0, memberCount(team))
    }

    // createTeam

    @Test
    fun createTeamIsAdminOnly() {
        val company = insertCompany()
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(insertTeam(company), memberId, "MEMBER")
        val member = auth.currentUser(memberId)!!

        val created = assertNotNull(teams.createTeam(admin, " Urgência "))
        assertEquals(company, teamCompany(created))
        assertNull(teams.createTeam(member, "Blocos"))
        assertNull(teams.createTeam(admin, "   "))
    }
}
