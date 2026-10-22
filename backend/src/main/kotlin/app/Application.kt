package app

import app.controller.team.teamRoutes
import app.controller.auth.OAUTH_STATE_COOKIE
import app.controller.auth.UserSession
import app.controller.auth.authRoutes
import app.controller.user.meRoutes
import app.persistence.connectDatabase
import app.persistence.migrate
import app.persistence.team.TeamRepository
import app.persistence.user.UserRepository
import app.service.team.TeamService
import app.service.auth.AuthService
import app.service.user.UserService
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.OAuthServerSettings
import io.ktor.server.auth.oauth
import io.ktor.server.netty.EngineMain
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SameSite
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.util.hex
import java.net.URI

fun main(args: Array<String>) = EngineMain.main(args)

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

    val users = UserService(UserRepository(db))
    val auth = AuthService(users)
    val teams = TeamService(TeamRepository(db))

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
        authRoutes(auth, publicUrl, domain, clientId, secure, jwks, issuer)
        meRoutes(users)
        teamRoutes(users, teams)
    }
}
