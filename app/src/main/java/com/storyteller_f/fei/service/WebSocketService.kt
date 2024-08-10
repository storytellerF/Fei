package com.storyteller_f.fei.service

import android.os.Build
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.LinkedHashSet

class Connection(val session: DefaultWebSocketServerSession) {
    companion object {
        val lastId = AtomicInteger(0)
    }

    val name = "user${lastId.getAndIncrement()}"
}

@Serializable
class Message(val from: String, val data: String)

fun Application.webSocketsService() {
    install(WebSockets) {
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
    routing {
        val connections = Collections.synchronizedSet<Connection?>(LinkedHashSet())
        webSocket("/chat") {
            println("Adding user!")
            val thisConnection = Connection(this)
            connections += thisConnection
            try {
                sendSerialized(Message("system", "**You** are connected! There are ${connections.count()} users here."))
                for (frame in incoming) {
                    frame as? Frame.Text ?: continue
                    val receivedText = frame.readText()

                    connections.forEach {
                        it.session.sendSerialized(Message(thisConnection.name, receivedText))
                    }
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
            } finally {
                println("Removing $thisConnection!")
                connections -= thisConnection
            }
        }
    }
}
