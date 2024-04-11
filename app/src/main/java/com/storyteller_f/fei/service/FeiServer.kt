package com.storyteller_f.fei.service

import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.storyteller_f.fei.cacheInvalid
import com.storyteller_f.fei.removeUri
import com.storyteller_f.fei.saveFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

class FeiServer(feiService: FeiService) {
    private val scope = feiService.scope
    private val context = feiService
    private var server: ApplicationEngine? = null
    var sseChannel: MutableSharedFlow<SseEvent>? = null

    /**
     * 本地也会作为一个webSocket 客户端连接
     */
    private var selfClient: HttpClient? = null
    private var selfSession: DefaultClientWebSocketSession? = null
    var port = FeiService.DEFAULT_PORT
    val messagesCache = MutableStateFlow(listOf<Message>())

    private fun startInternal() {
        Log.d(TAG, "startInternal() called")
        try {
            server = embeddedServer(Netty, port = port, host = FeiService.LISTENER_ADDRESS) {
                plugPlugins(context)
                sseChannel = setupSse()
                configureRouting(context)
                webSocketsService()
            }.start(wait = false)
            Log.i(TAG, "startInternal: $server")
            selfClient = setupSelfClient()
            context.postNotify("running on $port")
        } catch (th: Throwable) {
            Log.e(TAG, "startInternal: ${th.localizedMessage}", th)
        }
    }

    private fun setupSelfClient(): HttpClient {
        val httpClient = HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 20_000
                contentConverter = KotlinxWebsocketSerializationConverter(Json)
            }
        }

        scope.launch {
            httpClient.webSocket(
                method = HttpMethod.Get,
                host = "127.0.0.1",
                port = port,
                path = "/chat"
            ) {
                selfSession = this
                try {
                    while (true) {
                        val receiveDeserialized = receiveDeserialized<Message>()
                        messagesCache.value = messagesCache.value.plus(receiveDeserialized)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startInternal: self webSocket", e)
                }
                selfSession = null
            }
            Log.i(TAG, "startInternal: self webSocket end")
        }
        return httpClient
    }

    fun stop() {
        scope.launch {
            stopInternal()
        }
    }

    private suspend fun stopInternal() {
        Log.d(TAG, "stopInternal() called")
        context.postNotify("stopped")
        selfSession?.close()
        selfSession = null
        selfClient?.close()
        selfClient = null
        server?.stop()
        server = null
        sseChannel = null
    }

    fun restart() {
        scope.launch {
            restartAsync()
        }
        Toast.makeText(context, "restarted", Toast.LENGTH_SHORT).show()
    }

    suspend fun restartAsync() {
        stopInternal()
        startInternal()
    }

    fun stopAsync() {
        runBlocking {
            stopInternal()
        }
    }

    fun saveToLocal(uri: Uri?, info: SharedFileInfo) {
        uri ?: return
        scope.launch {
            context.saveFile(File(info.name).extension, uri)
            context.removeUri(info)
            context.cacheInvalid()//when save to local
            sseChannel?.emit(SseEvent("refresh"))
        }
    }

    fun sendMessage(content: String) {
        scope.launch {
            selfSession?.send(content)
        }
    }

    companion object {
        private const val TAG = "FeiServer"
    }
}
