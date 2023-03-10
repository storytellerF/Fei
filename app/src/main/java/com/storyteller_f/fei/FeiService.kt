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
import io.ktor.http.*
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.broadcast
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
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
//            Toast.makeText(this, "???????????????", Toast.LENGTH_SHORT).show()
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
        var port = defaultPort

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun startInternal() {
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
                }.start(wait = false)
                feiService.postNotify("running on $port")
            } catch (th: Throwable) {
                Log.e(TAG, "start: ${th.localizedMessage}", th)
            }
        }

        fun stop() {
            feiService.scope.launch {
                stopInternal()
            }
        }

        private fun stopInternal() {
            Log.d(TAG, "stopInternal() called")
            feiService.postNotify("stopped")
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

        fun restartAsync() {
            stopInternal()
            startInternal()
        }
        fun stopAsync() {
            stopInternal()
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
        }
    }

    companion object {
        private const val TAG = "FeiService"
        private const val foreground_notification_id = 10
        private const val foregroundChannelId = "foreground"
        const val defaultPort = 8080
        const val listenerAddress = "0.0.0.0"
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
        get("/shares") {
            val encodeToString = Json.encodeToString(shares.value)
            call.respond(encodeToString)
        }

        get("/shares/{count}") {
            val s = call.parameters["count"]?.toInt() ?: return@get
            val info = shares.value[s]
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