package app.service.user

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
