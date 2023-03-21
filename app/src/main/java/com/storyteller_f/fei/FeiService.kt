package com.storyteller_f.fei

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toFile
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.thymeleaf.*
import io.ktor.server.websocket.*
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.*
import io.ktor.websocket.serialization.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.broadcast
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.util.*

val Context.portFlow
    get() = dataStore.data.map {
        it[stringPreferencesKey("port")]?.toInt() ?: FeiService.defaultPort
    }

class FeiService : Service() {
    private val binder = Fei(this)
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind() called with: intent = $intent")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(
            TAG,
            "onStartCommand() called with: intent = $intent, flags = $flags, startId = $startId"
        )
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate() called")
        super.onCreate()
        val channelId = foregroundChannelId
        val channel =
            NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_MIN)
                .apply {
                    setName("running")
                    this.setDescription(getString(R.string.foreground_service_channel_description))
                }.build()
        val managerCompat = NotificationManagerCompat.from(this)
        if (managerCompat.getNotificationChannel(channelId) == null)
            managerCompat.createNotificationChannel(channel)
        scope.launch {
            portFlow.distinctUntilChanged().collectLatest {
                Log.i(TAG, "onCreate: port $it")
                binder.port = it
                binder.restartAsync()
            }
        }

    }

    private fun postNotify(managerCompat: NotificationManagerCompat, message: String) {
        if (managerCompat.areNotificationsEnabled()) {
            val notification =
                NotificationCompat.Builder(this, foregroundChannelId)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(getString(R.string.app_name)).setContentText(message).build()
            startForeground(foreground_notification_id, notification)
        } else {
//            Toast.makeText(this, "未开启通知", Toast.LENGTH_SHORT).show()
        }
    }

    fun postNotify(message: String) {
        postNotify(NotificationManagerCompat.from(this), message)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        binder.stopAsync()
        scope.cancel()
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    class Fei(private val feiService: FeiService) : Binder() {
        private var server: ApplicationEngine? = null
        var channel: BroadcastChannel<SseEvent>? = null
        private var selfClient: HttpClient? = null
        private var selfSession: DefaultClientWebSocketSession? = null
        var port = defaultPort
        val messagesCache = MutableStateFlow(listOf<Message>())

        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun startInternal() {
            Log.d(TAG, "startInternal() called")
            try {
                server = embeddedServer(Netty, port = port, host = listenerAddress) {
                    plugPlugins()
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
                val httpClient = HttpClient(CIO) {
                    install(io.ktor.client.plugins.websocket.WebSockets) {
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

        private fun Application.plugPlugins() {
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
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(15)
                maxFrameSize = Long.MAX_VALUE
                masking = false
                contentConverter = KotlinxWebsocketSerializationConverter(Json)
            }
        }

        fun saveToLocal(uri: Uri?, info: SharedFileInfo) {
            uri ?: return
            feiService.scope.launch {
                saveFile(File(info.name).extension, uri)
                feiService.removeUri(info)
                feiService.cacheInvalid()
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
    }

    companion object {
        private const val TAG = "FeiService"
        private const val foreground_notification_id = 10
        private const val foregroundChannelId = "foreground"
        const val defaultPort = 8080
        const val listenerAddress = "0.0.0.0"
        const val defaultAddress = "127.0.0.1"
    }
}

data class SseEvent(val data: String, val event: String? = null, val id: String? = null)

@kotlinx.serialization.Serializable
data class SharedFileInfo(val uri: String, val name: String)

private fun Application.configureRouting(feiService: FeiService) {
    routing {
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
}

@Suppress("BlockingMethodInNonBlockingContext")
suspend fun ApplicationCall.respondSse(events: ReceiveChannel<SseEvent>) {
    response.cacheControl(CacheControl.NoCache(null))
    respondTextWriter(contentType = ContentType.Text.EventStream) {
        for (event in events) {
            if (event.id != null) {
                write("id: ${event.id}\n")
            }
            if (event.event != null) {
                write("event: ${event.event}\n")
            }
            for (dataLine in event.data.lines()) {
                write("data: $dataLine\n")
            }
            write("\n")
            flush()
        }
    }
}