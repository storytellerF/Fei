package com.storyteller_f.fei.service

import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.net.toFile
import androidx.datastore.preferences.core.stringPreferencesKey
import com.storyteller_f.fei.Message
import com.storyteller_f.fei.cacheInvalid
import com.storyteller_f.fei.dataStore
import com.storyteller_f.fei.removeUri
import com.storyteller_f.fei.respondUri
import com.storyteller_f.fei.shares
import com.storyteller_f.fei.webSocketsService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.form
import io.ktor.server.auth.session
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.thymeleaf.Thymeleaf
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.broadcast
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID

@OptIn(ObsoleteCoroutinesApi::class)
class FeiServer(private val feiService: FeiService) {
    private var server: ApplicationEngine? = null
    var channel: BroadcastChannel<SseEvent>? = null
    private var selfClient: HttpClient? = null
    private var selfSession: DefaultClientWebSocketSession? = null
    var port = FeiService.DEFAULT_PORT
    val messagesCache = MutableStateFlow(listOf<Message>())

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun startInternal() {
        Log.d(TAG, "startInternal() called")
        try {
            server = embeddedServer(Netty, port = port, host = FeiService.LISTENER_ADDRESS) {
                plugPlugins(feiService)
                channel = produce {
                    var n = 0
                    while (true) {
                        send(SseEvent("$n", "ping"))
                        delay(1000)
                        n++
                    }
                }.broadcast()
                routing {
                    get("/sse") {
                        val events = channel!!.openSubscription()
                        try {
                            call.respondSse(events)
                        } finally {
                            events.cancel()
                        }

                    }
                }
                configureRouting(feiService)
                webSocketsService()
            }.start(wait = false)
            Log.i(TAG, "startInternal: $server")
            val httpClient = HttpClient(CIO) {
                install(WebSockets) {
                    pingInterval = 20_000
                    contentConverter = KotlinxWebsocketSerializationConverter(Json)
                }
            }
            selfClient = httpClient
            feiService.scope.launch {
                httpClient.webSocket(method = HttpMethod.Get, host = "127.0.0.1", port = port, path = "/chat") {
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
            feiService.postNotify("running on $port")
        } catch (th: Throwable) {
            Log.e(TAG, "startInternal: ${th.localizedMessage}", th)
        }
    }

    fun stop() {
        feiService.scope.launch {
            stopInternal()
        }
    }

    private suspend fun stopInternal() {
        Log.d(TAG, "stopInternal() called")
        feiService.postNotify("stopped")
        selfSession?.close()
        selfSession = null
        selfClient?.close()
        selfClient = null
        server?.stop()
        server = null
        channel?.cancel()
        channel = null
    }

    fun restart() {
        feiService.scope.launch {
            stopInternal()
            startInternal()
        }
        Toast.makeText(feiService, "restarted", Toast.LENGTH_SHORT).show()
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

    private fun Application.plugPlugins(feiService: FeiService) {
        install(StatusPages) {
            exception<Throwable> { call: ApplicationCall, cause ->
                call.respondText(
                    cause.localizedMessage ?: cause.javaClass.canonicalName,
                    status = HttpStatusCode.InternalServerError
                )
                Log.e(TAG, "plugPlugins: ", cause)
            }
        }
        install(CallLogging)
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
                    val pass = feiService.dataStore.data.map {
                        it[stringPreferencesKey("pass")]
                    }.firstOrNull()
                    if (pass == null || credential.password == pass) {
                        UserIdPrincipal("pass")
                    } else null
                }
                challenge {
                    call.respond(HttpStatusCode.Unauthorized, "Credentials are not valid")
                }
            }
            session<UserSession>("auth-session") {
                validate { session ->
                    session
                }
                challenge {
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

    fun saveToLocal(uri: Uri?, info: SharedFileInfo) {
        uri ?: return
        feiService.scope.launch {
            saveFile(File(info.name).extension, uri)
            feiService.removeUri(info)
            feiService.cacheInvalid()//when save to local
            channel?.send(SseEvent("refresh"))
        }
    }

    private suspend fun saveFile(extension: String?, uri: Uri) {
        try {
            withContext(Dispatchers.IO) {
                val file = File(feiService.filesDir, "saved/file-${UUID.randomUUID()}.$extension")
                val parentFile = file.parentFile!!
                if (!parentFile.exists()) {
                    parentFile.mkdirs()
                }
                if (file.createNewFile()) {
                    uri.writeToFile(file)
                } else {
                    Log.e(TAG, "create file failed ${file.absolutePath}")
                }

            }
        } catch (e: Exception) {
            Log.e(TAG, "saveFile: ", e)
        }

    }

    private fun Uri.writeToFile(file: File) {
        file.outputStream().channel.use { oChannel ->
            feiService.contentResolver.openFileDescriptor(this, "r")?.use { parcelFileDescriptor ->
                FileInputStream(parcelFileDescriptor.fileDescriptor).channel.use { iChannel ->
                    val byteBuffer = ByteBuffer.allocateDirect(1024)
                    while (iChannel.read(byteBuffer) != -1) {
                        byteBuffer.flip()
                        oChannel.write(byteBuffer)
                        byteBuffer.clear()
                    }
                }
            }
        }
    }

    fun sendMessage(content: String) {
        feiService.scope.launch {
            selfSession?.send(content)
        }
    }

    companion object {
        private const val TAG = "FeiServer"
    }
}

data class UserSession(val name: String) : Principal

private fun Application.configureRouting(feiService: FeiService) {
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

private fun Route.contentRoute(feiService: FeiService) {
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
            } else call.respondUri(feiService, file)
        }

    }
}