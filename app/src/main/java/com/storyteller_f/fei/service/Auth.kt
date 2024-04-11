package com.storyteller_f.fei.service

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import com.storyteller_f.fei.respondUri
import com.storyteller_f.fei.shares
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.Principal
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.thymeleaf.ThymeleafContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID


data class UserSession(val name: String) : Principal

fun Application.configureRouting(feiService: FeiService) {
    routing {
        get("/login") {
            call.respond(ThymeleafContent("login", mapOf()))
        }
        authenticate {
            post("/login") {
                call.sessions.set(UserSession(name = UUID.randomUUID().toString()))
                call.respondRedirect("/")
            }
        }
        authenticate("auth-session") {
            contentRoute(feiService)
        }

    }
}

private fun Route.contentRoute(context: Context) {
    get("/") {
        call.respond(
            ThymeleafContent(
                "index",
                mapOf("shares" to List(shares.value.size) { index ->
                    index.toString()
                })
            )
        )
    }
    get("/messages") {
        call.respond(ThymeleafContent("chat", mapOf()))
    }
    get("/shares") {
        val encodeToString = Json.encodeToString(shares.value)
        call.respond(encodeToString)
    }

    get("/shares/{count}") {
        val index = call.parameters["count"]?.toInt() ?: return@get
        val info = shares.value.getOrNull(index)
        if (info == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    info.name
                )
                    .toString()
            )
            val file = Uri.parse(info.uri)
            if (file.scheme == "file") {
                call.respondFile(file.toFile())
            } else call.respondUri(context, file)
        }

    }
}
