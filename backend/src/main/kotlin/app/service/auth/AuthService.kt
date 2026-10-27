package app.service.auth

import app.persistence.user.UserRepository
import app.service.user.CurrentUser
import java.util.UUID

sealed interface Login {
    data class Ok(val userId: UUID) : Login
    data object NoInvite : Login
    data object Deactivated : Login
}

class AuthService(private val users: UserRepository) {

    // Matches an account by Auth0 sub; otherwise claims an unclaimed invite by verified email.
    fun resolveLogin(sub: String, email: String?, emailVerified: Boolean): Login {
        val bySub = users.findBySubject(sub)
        val invited = if (bySub == null && email != null && emailVerified) {
            users.findUnclaimedByEmail(email)
        } else {
            null
        }
        return when {
            bySub != null -> if (bySub.deactivated) Login.Deactivated else Login.Ok(bySub.id)
            invited == null -> Login.NoInvite
            invited.deactivated -> Login.Deactivated
            // The IS NULL guard in claimInvite means a concurrent claim of the same invite loses instead of overwriting.
            else -> if (users.claimInvite(invited.id, sub)) Login.Ok(invited.id) else Login.NoInvite
        }
    }

    fun currentUser(id: UUID): CurrentUser? = users.findCurrentUser(id)
}
