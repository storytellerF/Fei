package com.storyteller_f.fei.service

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.Principal
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.thymeleaf.ThymeleafContent
import java.util.UUID


data class UserSession(val name: String) : Principal

fun Application.configureRouting(feiService: FeiService) {
    routing {
        get("/login") {
            call.respond(ThymeleafContent("login", mapOf()))
        }
        authenticate {
            post("/login") {
                println("post login")
                call.sessions.set(UserSession(name = UUID.randomUUID().toString()))
                call.respondRedirect("/")
            }
        }
        authenticate("auth-session") {
            contentRoute(feiService)
        }

    }
}
