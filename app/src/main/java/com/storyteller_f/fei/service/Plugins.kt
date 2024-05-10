package com.storyteller_f.fei.service

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.stringPreferencesKey
import com.storyteller_f.fei.dataStore
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.form
import io.ktor.server.auth.session
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.thymeleaf.Thymeleaf
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.Duration


fun Application.plugPlugins(context: Context) {
    install(StatusPages) {
        exception<Throwable> { call: ApplicationCall, cause ->
            call.respondText(
                cause.localizedMessage ?: cause.javaClass.canonicalName,
                status = HttpStatusCode.InternalServerError
            )
        }
    }
    install(CallLogging) {
        level = Level.DEBUG
    }
    install(Thymeleaf) {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            characterEncoding = "utf-8"
        })
    }
    install(PartialContent)
    install(AutoHeadResponse)
    install(io.ktor.server.websocket.WebSockets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pingPeriod = Duration.ofSeconds(15)
        } else pingPeriodMillis = 15000
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            timeout = Duration.ofSeconds(15)
        } else timeoutMillis = 15000
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }
    install(Authentication) {
        form {
            validate { credential ->
                val pass = context.dataStore.data.map {
                    it[stringPreferencesKey("pass")]
                }.firstOrNull()
                println("form validate $credential $pass")
                if (pass == null || credential.password == pass) {
                    UserIdPrincipal("pass")
                } else null
            }
            challenge {
                println("form challenge")
                call.respond(HttpStatusCode.Unauthorized, "Credentials are not valid")
            }
        }
        session<UserSession>("auth-session") {
            validate { session ->
                println("session validate")
                session
            }
            challenge {
                println("session challenge")
                call.respondRedirect("/login")
            }
        }
    }
    install(Sessions) {
        cookie<UserSession>("user_session") {
            cookie.path = "/"
        }
    }
}

fun Application.setupSse(): MutableSharedFlow<SseEvent> {
    val flow = MutableSharedFlow<SseEvent>()
    routing {
        get("/sse") {
            call.respondSse(flow)
        }
    }
    return flow
}