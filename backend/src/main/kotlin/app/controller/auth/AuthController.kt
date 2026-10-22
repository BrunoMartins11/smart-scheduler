package app.controller.auth

import app.service.auth.AuthService
import app.service.auth.Login
import app.service.user.UserService
import app.service.user.CurrentUser
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.OAuthAccessTokenResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.set
import io.ktor.server.sessions.sessions
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import kotlinx.serialization.Serializable

// The session cookie holds only the user_account id (DATA_MODEL.md §3, Auth0 login flow).
@Serializable
data class UserSession(val userId: String)

const val OAUTH_STATE_COOKIE = "oauth_state"

fun Route.authRoutes(
    auth: AuthService,
    publicUrl: String,
    domain: String,
    clientId: String,
    secure: Boolean,
    jwks: JwkProvider,
    issuer: String,
) {
    authenticate("auth0") {
        // With no login yet, the OAuth plugin redirects to Auth0 before this body runs.
        get("/auth/login") {}

        get("/auth/callback") {
            call.response.cookies.append(
                name = OAUTH_STATE_COOKIE, value = "", path = "/auth", maxAge = 0,
                secure = secure, httpOnly = true, extensions = mapOf("SameSite" to "Lax"),
            )
            if (!stateMatches(call.request.cookies[OAUTH_STATE_COOKIE], call.parameters["state"])) {
                call.application.environment.log.warn("OAuth callback rejected: state cookie missing or mismatched (cookie present=${call.request.cookies[OAUTH_STATE_COOKIE] != null})")
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val token = call.principal<OAuthAccessTokenResponse.OAuth2>() ?: run {
                call.application.environment.log.warn("OAuth callback rejected: no OAuth2 principal")
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val idToken = token.extraParameters["id_token"] ?: run {
                call.application.environment.log.warn("OAuth callback rejected: token response has no id_token")
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val claims = try {
                verifyIdToken(idToken, jwks, issuer, clientId)
            } catch (e: JWTVerificationException) {
                call.application.environment.log.warn("OAuth callback rejected: ID token invalid: ${e.message}")
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val verified = claims.getClaim("email_verified").asBoolean() == true
            when (val login = auth.resolveLogin(claims.subject, claims.getClaim("email").asString(), verified)) {
                is Login.Ok -> {
                    call.sessions.set(UserSession(login.userId.toString()))
                    call.respondRedirect(publicUrl)
                }
                Login.NoInvite -> call.respondText("Peça um convite ao seu gestor.", status = HttpStatusCode.Forbidden)
                Login.Deactivated -> call.respondText("Conta desativada.", status = HttpStatusCode.Forbidden)
            }
        }
    }

    post("/auth/logout") {
        call.sessions.clear<UserSession>()
        val returnTo = URLEncoder.encode(publicUrl, StandardCharsets.UTF_8)
        call.respondRedirect("https://$domain/v2/logout?client_id=$clientId&returnTo=$returnTo")
    }
}

// Shared by every protected route. Responds 401 and clears the session when there is no usable user.
suspend fun ApplicationCall.currentUser(users: UserService): CurrentUser? {
    val user = sessions.get<UserSession>()?.let { users.loadCurrentUser(UUID.fromString(it.userId)) }
    if (user == null || user.deactivated) {
        sessions.clear<UserSession>()
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return user
}

// Checks the signature (JWKS), issuer, audience and expiry of the ID token from the token endpoint.
fun verifyIdToken(idToken: String, jwks: JwkProvider, issuer: String, clientId: String): DecodedJWT {
    val key = jwks.get(JWT.decode(idToken).keyId).publicKey as RSAPublicKey
    return JWT.require(Algorithm.RSA256(key, null))
        .withIssuer(issuer)
        .withAudience(clientId)
        .build()
        .verify(idToken)
}

// Binds the OAuth callback to the browser that started the login (login CSRF). Constant-time compare.
fun stateMatches(expected: String?, actual: String?): Boolean =
    expected != null && actual != null &&
        java.security.MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
