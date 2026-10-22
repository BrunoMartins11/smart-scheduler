package app.service.user

import app.persistence.user.UserRepository
import java.util.UUID

data class CurrentUser(
    val id: UUID,
    val companyId: UUID,
    val email: String,
    val displayName: String,
    val deactivated: Boolean,
    val isCompanyAdmin: Boolean,
    // team_ids where this user is an active MANAGER member.
    val managedTeamIds: Set<UUID>,
)

data class Account(val id: UUID, val deactivated: Boolean)

class UserService(private val users: UserRepository) {
    fun findBySubject(sub: String): Account? = users.findBySubject(sub)

    fun findUnclaimedByEmail(email: String): Account? = users.findUnclaimedByEmail(email)

    fun claimInvite(id: UUID, sub: String): Boolean = users.claimInvite(id, sub)

    fun loadCurrentUser(id: UUID): CurrentUser? = users.findCurrentUser(id)
}
