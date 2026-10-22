package app

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.OAuthAccessTokenResponse
import io.ktor.server.auth.OAuthServerSettings
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.oauth
import io.ktor.server.auth.principal
import io.ktor.server.netty.EngineMain
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SameSite
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.set
import io.ktor.server.sessions.sessions
import io.ktor.util.hex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

fun main(args: Array<String>) = EngineMain.main(args)

const val OAUTH_STATE_COOKIE = "oauth_state"

fun Application.module() {
    val db = connectDatabase(environment.config)
    migrate(db)
    monitor.subscribe(ApplicationStopped) { db.close() }

    val publicUrl = environment.config.property("app.publicUrl").getString().trimEnd('/')
    val secure = publicUrl.startsWith("https://")
    val domain = environment.config.property("auth0.domain").getString()
    val clientId = environment.config.property("auth0.clientId").getString()
    val clientSecret = environment.config.property("auth0.clientSecret").getString()
    val issuer = "https://$domain/"
    val jwks = JwkProviderBuilder(URI("${issuer}.well-known/jwks.json").toURL()).build()
    val encryptKey = hex(environment.config.property("session.encryptKey").getString())
    val signKey = hex(environment.config.property("session.signKey").getString())

    install(Sessions) {
        cookie<UserSession>("sid") {
            cookie.httpOnly = true
            cookie.extensions["SameSite"] = SameSite.Lax
            cookie.path = "/"
            cookie.secure = secure
            transform(SessionTransportTransformerEncrypt(encryptKey, signKey))
        }
    }

    install(Authentication) {
        oauth("auth0") {
            urlProvider = { "$publicUrl/auth/callback" }
            providerLookup = {
                // shortcut: no PKCE; the oauth_state cookie binds the callback to this browser. Add PKCE when Ktor supports it or before go-live.
                OAuthServerSettings.OAuth2ServerSettings(
                    name = "auth0",
                    authorizeUrl = "https://$domain/authorize",
                    accessTokenUrl = "https://$domain/oauth/token",
                    clientId = clientId,
                    clientSecret = clientSecret,
                    defaultScopes = listOf("openid", "email", "profile"),
                    // Auth0's token endpoint only accepts POST; Ktor defaults to GET.
                    requestMethod = HttpMethod.Post,
                    onStateCreated = { call, state ->
                        call.response.cookies.append(
                            name = OAUTH_STATE_COOKIE,
                            value = state,
                            path = "/auth",
                            maxAge = 600,
                            secure = secure,
                            httpOnly = true,
                            extensions = mapOf("SameSite" to "Lax"),
                        )
                    },
                )
            }
            client = HttpClient()
            fallback = { cause ->
                application.environment.log.warn("OAuth token exchange failed: ${cause.message}")
                respond(HttpStatusCode.Unauthorized)
            }
        }
    }

    routing {
        get("/health") {
            call.respondText("ok")
        }

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
                when (val login = resolveLogin(db, claims.subject, claims.getClaim("email").asString(), verified)) {
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

        get("/api/me") {
            val user = call.currentUser(db) ?: return@get
            val body = buildJsonObject {
                put("id", user.id.toString())
                put("email", user.email)
                put("displayName", user.displayName)
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
    }
}
