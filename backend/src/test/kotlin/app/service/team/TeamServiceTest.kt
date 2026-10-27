package app.service.team

import app.addMember
import app.insertAccount
import app.insertCompany
import app.MemberState
import app.insertTeam
import app.memberCount
import app.memberState
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

    // visibleTeams

    @Test
    fun visibleTeamsAdminSeesAllCompanyTeamsMemberSeesOwnActive() {
        val company = insertCompany()
        val mine = insertTeam(company)
        val other = insertTeam(company)
        val foreign = insertTeam(insertCompany())
        val userId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(mine, userId, "MEMBER")
        addMember(other, userId, "MEMBER", active = false)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!

        assertEquals(setOf(mine), teams.visibleTeams(auth.currentUser(userId)!!).map { it.id }.toSet())
        val adminIds = teams.visibleTeams(admin).map { it.id }.toSet()
        assertTrue(mine in adminIds && other in adminIds)
        assertFalse(foreign in adminIds)
    }

    // listMembers

    @Test
    fun listMembersIncludesInactiveForManagerOfTeamOnly() {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        addMember(team, insertAccount("gone-${uniq()}@x.pt", companyId = company), "MEMBER", active = false)
        val manager = auth.currentUser(managerId)!!

        assertEquals(2, teams.listMembers(manager, team)!!.size)
        assertNull(teams.listMembers(manager, insertTeam(company)))
    }

    @Test
    fun listMembersIsNullForMemberAndOtherCompanyAdmin() {
        val company = insertCompany()
        val team = insertTeam(company)
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")
        val otherAdmin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true))!!

        assertNull(teams.listMembers(auth.currentUser(memberId)!!, team))
        assertNull(teams.listMembers(otherAdmin, team))
    }

    // updateMember

    @Test
    fun updateMemberChangesOnlyProvidedFields() {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val userId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, userId, "MEMBER")

        assertEquals(MemberResult.Ok, teams.updateMember(admin, team, userId, 50, null, null))
        assertEquals(MemberState("MEMBER", 50, false, true), memberState(team, userId))
        assertEquals(MemberResult.Ok, teams.updateMember(admin, team, userId, null, true, "MANAGER"))
        assertEquals(MemberState("MANAGER", 50, true, true), memberState(team, userId))
    }

    @Test
    fun updateMemberValidatesFteAndRole() {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val userId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, userId, "MEMBER")

        assertEquals(MemberResult.Invalid, teams.updateMember(admin, team, userId, 0, null, null))
        assertEquals(MemberResult.Invalid, teams.updateMember(admin, team, userId, 101, null, null))
        assertEquals(MemberResult.Invalid, teams.updateMember(admin, team, userId, null, null, "ADMIN"))
        assertEquals(MemberState("MEMBER", 100, false, true), memberState(team, userId))
    }

    @Test
    fun managerCannotChangeManagerRoleButAdminCan() {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val memberId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, memberId, "MEMBER")
        val otherManagerId = insertAccount("mgr2-${uniq()}@x.pt", companyId = company)
        addMember(team, otherManagerId, "MANAGER")
        val manager = auth.currentUser(managerId)!!
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!

        assertEquals(MemberResult.NotFound, teams.updateMember(manager, team, memberId, null, null, "MANAGER"))
        assertEquals(MemberResult.NotFound, teams.updateMember(manager, team, otherManagerId, null, null, "MEMBER"))
        assertEquals("MEMBER", memberState(team, memberId).role)
        assertEquals("MANAGER", memberState(team, otherManagerId).role)
        assertEquals(MemberResult.Ok, teams.updateMember(admin, team, otherManagerId, null, null, "MEMBER"))
        assertEquals("MEMBER", memberState(team, otherManagerId).role)
    }

    @Test
    fun updateMemberOutsideReachOrUnknownIsNotFound() {
        val company = insertCompany()
        val team = insertTeam(company)
        val otherTeam = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val userInOther = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(otherTeam, userInOther, "MEMBER")
        val manager = auth.currentUser(managerId)!!

        assertEquals(MemberResult.NotFound, teams.updateMember(manager, otherTeam, userInOther, 50, null, null))
        assertEquals(MemberResult.NotFound, teams.updateMember(manager, team, userInOther, 50, null, null))
        assertEquals(MemberResult.NotFound, teams.updateMember(manager, team, UUID.randomUUID(), 50, null, null))
        assertEquals(100, memberState(otherTeam, userInOther).ftePercent)
    }

    // removeMember

    @Test
    fun removeMemberSoftDeactivates() {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val userId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, userId, "MEMBER")

        assertEquals(MemberResult.Ok, teams.removeMember(admin, team, userId))
        assertEquals(MemberState("MEMBER", 100, false, false), memberState(team, userId))
        assertEquals(1, memberCount(team))
    }

    @Test
    fun nonAdminManagerCannotRemoveManagerButAdminCan() {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val otherManagerId = insertAccount("mgr2-${uniq()}@x.pt", companyId = company)
        addMember(team, otherManagerId, "MANAGER")
        val manager = auth.currentUser(managerId)!!
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!

        assertEquals(MemberResult.NotFound, teams.removeMember(manager, team, otherManagerId))
        assertTrue(memberState(team, otherManagerId).active)
        assertEquals(MemberResult.Ok, teams.removeMember(admin, team, otherManagerId))
        assertFalse(memberState(team, otherManagerId).active)
    }

    @Test
    fun updateMemberReactivatesAndUnchangedRoleStillAppliesFte() {
        val company = insertCompany()
        val team = insertTeam(company)
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!
        val userId = insertAccount("member-${uniq()}@x.pt", companyId = company)
        addMember(team, userId, "MEMBER")

        assertEquals(MemberResult.Ok, teams.removeMember(admin, team, userId))
        assertEquals(MemberResult.Ok, teams.updateMember(admin, team, userId, null, null, null, active = true))
        assertEquals(MemberState("MEMBER", 100, false, true), memberState(team, userId))

        assertEquals(MemberResult.Ok, teams.updateMember(admin, team, userId, 40, null, "MEMBER"))
        assertEquals(MemberState("MEMBER", 40, false, true), memberState(team, userId))
    }

    @Test
    fun nonAdminManagerCannotReactivateManager() {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val otherManagerId = insertAccount("mgr2-${uniq()}@x.pt", companyId = company)
        addMember(team, otherManagerId, "MANAGER")
        val manager = auth.currentUser(managerId)!!
        val admin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = company, admin = true))!!

        assertEquals(MemberResult.Ok, teams.removeMember(admin, team, otherManagerId))
        assertEquals(MemberResult.NotFound, teams.updateMember(manager, team, otherManagerId, null, null, null, active = true))
        assertFalse(memberState(team, otherManagerId).active)
        assertEquals(MemberResult.Ok, teams.updateMember(admin, team, otherManagerId, null, null, null, active = true))
        assertTrue(memberState(team, otherManagerId).active)
    }

    @Test
    fun updateMemberRefusesSelfDeactivateButAllowsOtherChanges() {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val manager = auth.currentUser(managerId)!!

        assertEquals(MemberResult.Invalid, teams.updateMember(manager, team, managerId, null, null, null, active = false))
        assertTrue(memberState(team, managerId).active)
        assertEquals(MemberResult.Ok, teams.updateMember(manager, team, managerId, 80, null, null, active = true))
        assertEquals(MemberResult.Ok, teams.updateMember(manager, team, managerId, 90, null, null))
        assertEquals(MemberState("MANAGER", 90, false, true), memberState(team, managerId))
    }

    @Test
    fun removeMemberRefusesSelfAndOutsideReach() {
        val company = insertCompany()
        val team = insertTeam(company)
        val managerId = insertAccount("mgr-${uniq()}@x.pt", companyId = company)
        addMember(team, managerId, "MANAGER")
        val manager = auth.currentUser(managerId)!!
        val foreignAdmin = auth.currentUser(insertAccount("admin-${uniq()}@x.pt", companyId = insertCompany(), admin = true))!!

        assertEquals(MemberResult.Invalid, teams.removeMember(manager, team, managerId))
        assertTrue(memberState(team, managerId).active)
        assertEquals(MemberResult.NotFound, teams.removeMember(foreignAdmin, team, managerId))
        assertTrue(memberState(team, managerId).active)
    }
}
