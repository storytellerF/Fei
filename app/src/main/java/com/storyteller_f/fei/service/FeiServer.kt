package com.storyteller_f.fei.service

import android.util.Log
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
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

sealed interface ServerState {
    data object Init : ServerState
    class Started(
        val port: Int,
        val server: ApplicationEngine,
        val chatSession: DefaultClientWebSocketSession,
        val client: HttpClient,
        val channel: MutableSharedFlow<SseEvent>,
        val messageList: MutableStateFlow<List<Message>>,
        val time: Long
    ) : ServerState

    data class Stopped(val reason: String) : ServerState

    data class Error(val cause: Throwable) : ServerState {
        constructor(message: String) : this(java.lang.Exception(message))

        val exceptionMessage = (cause.localizedMessage ?: cause::class.qualifiedName
        ?: cause::class.toString())
    }
}

class FeiServer(feiService: FeiService) {
    private val scope = feiService.scope
    private val context = feiService
    val state = MutableStateFlow<ServerState>(ServerState.Init)

    val messagesCache get() = (state.value as? ServerState.Started)?.messageList?.asStateFlow()

    private suspend fun startInternal(port: Int) {
        Log.d(TAG, "startInternal() called")
        try {
            val (server, channel) = setupServer(port)
            val (setupSelfClient, session) = setupSelfClient(port)
            val time = System.currentTimeMillis()
            state.value = ServerState.Started(
                port,
                server,
                session.first,
                setupSelfClient,
                channel,
                session.second,
                time
            )
            Log.i(TAG, "startInternal: $time")
        } catch (th: Throwable) {
            Log.e(TAG, "startInternal: ${th.localizedMessage}", th)
            emitErrorState(th)
        }
    }

    private suspend fun setupServer(port: Int): Pair<NettyApplicationEngine, MutableSharedFlow<SseEvent>> {
        val channelWaitWorker = CompletableDeferred<MutableSharedFlow<SseEvent>>()
        val start = embeddedServer(Netty, port = port, host = FeiService.LISTENER_ADDRESS) {
            plugPlugins(context)
            channelWaitWorker.complete(setupSse())
            configureRouting(context)
            webSocketsService()
        }.start(wait = false)
        return start to channelWaitWorker.await()
    }

    private suspend fun setupSelfClient(port: Int): Pair<HttpClient, Pair<DefaultClientWebSocketSession, MutableStateFlow<List<Message>>>> {
        val httpClient = HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 20_000
                contentConverter = KotlinxWebsocketSerializationConverter(Json)
            }
        }

        val messagesCache = MutableStateFlow<List<Message>>(emptyList())
        val sessionWaitWorker = CompletableDeferred<DefaultClientWebSocketSession>()
        scope.launch {
            httpClient.webSocket(
                method = HttpMethod.Get,
                host = "127.0.0.1",
                port = port,
                path = "/chat"
            ) {
                sessionWaitWorker.complete(this)
                try {
                    while (true) {
                        val receiveDeserialized = receiveDeserialized<Message>()
                        messagesCache.value = messagesCache.value.plus(receiveDeserialized)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startInternal: self webSocket", e)
                }
            }
            Log.i(TAG, "startInternal: self webSocket end")
        }
        return httpClient to (sessionWaitWorker.await() to messagesCache)
    }

    private suspend fun stopInternal(cause: String) {
        Log.d(TAG, "stopInternal() called")
        val serverState = state.value
        if (serverState is ServerState.Started) {
            serverState.chatSession.close()
            serverState.client.close()
            serverState.server.stop()
            state.value = ServerState.Stopped(cause)
        }
    }


    private suspend fun stopIfNeed(cause: String) {
        when (state.value) {
            is ServerState.Started -> {
                stopInternal(cause)
            }

            is ServerState.Init -> {
                return
            }

            is ServerState.Error -> {
                return
            }

            is ServerState.Stopped -> {
                return
            }
        }
    }

    private suspend fun startIfNeed(port: Int) {
        when (val current = state.value) {
            is ServerState.Started -> {
                if (current.port == port) {
                    return
                } else {
                    stopInternal("port changed")
                    startInternal(port)
                }
            }

            is ServerState.Init -> {
                startInternal(port)
            }

            is ServerState.Error -> {
                startInternal(port)
            }

            is ServerState.Stopped -> {
                startInternal(port)
            }
        }
    }

    private fun emitErrorState(cause: Throwable) {
        state.value = ServerState.Error(cause)
    }

    suspend fun onReceiveEventPort(port: Int, event: Int?) {
        if (event != null) {
            when (event) {
                FeiService.EVENT_STOP -> {
                    //stop server
                    stopIfNeed("stop event.")
                }

                FeiService.EVENT_RESTART -> {
                    Log.i(TAG, "onReceiveEventPort: restart")
                    stopIfNeed("restart event.")
                    startIfNeed(port)
                }
            }
        } else {
            when {
                port > FeiService.VALID_PORT -> {
                    //start server
                    startIfNeed(port)
                }

                else -> {
                    stopIfNeed("invalid port")
                    val cause = IllegalAccessException("invalid port $port")
                    emitErrorState(cause)
                }
            }
        }

    }


    fun stopBlocking() {
        runBlocking {
            stopInternal("service stopped")
        }
    }

    suspend fun sendMessage(content: String) {
        val serverState = state.value
        if (serverState is ServerState.Started) {
            val session = serverState.chatSession
            session.send(content)
        }
    }

    suspend fun emitRefreshEvent() {
        println("emitRefreshEvent")
        val serverState = state.value
        if (serverState is ServerState.Started) {
            val channel = serverState.channel
            channel.emit(SseEvent("refresh"))
        }
    }


    companion object {
        private const val TAG = "FeiServer"
    }
}
