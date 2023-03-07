package com.storyteller_f.fei

import android.app.Service
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.broadcast
import kotlinx.coroutines.channels.produce
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

class FeiService : Service() {
    private val binder = Fei(this)
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind() called with: intent = $intent")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() called with: intent = $intent, flags = $flags, startId = $startId")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate() called")
        super.onCreate()
        val channelId = "foreground"
        val channel = NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_MIN).apply {
            setName("running on 8080")
            this.setDescription("前台服务")
        }.build()
        val managerCompat = NotificationManagerCompat.from(this)
        if (managerCompat.getNotificationChannel(channelId) == null)
            managerCompat.createNotificationChannel(channel)
        val notification =
            NotificationCompat.Builder(this, channelId).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("kuang").setContentText("waiting").build()
        startForeground(foreground_notification_id, notification)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        binder.stop()
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    class Fei(private val feiService: FeiService) : Binder() {
        private var server: ApplicationEngine? = null
        var channel: BroadcastChannel<SseEvent>? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun start() {
            Log.d(TAG, "start() called")
            try {

                this.server = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
                    plugPlugins()
                    channel = produce {
                        var n = 0
                        while (true) {
                            send(SseEvent("$n", "ping"))
                            kotlinx.coroutines.delay(1000)
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
            } catch (th: Throwable) {
                Log.e(TAG, "start: ${th.localizedMessage}", th)
            }

        }

        private fun Application.plugPlugins() {
            install(StatusPages) {
                exception<Throwable> { call: ApplicationCall, cause ->
                    call.respondText(cause.localizedMessage ?: cause.javaClass.canonicalName, status = HttpStatusCode.InternalServerError)
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

        fun stop() {
            server?.stop()
            channel?.cancel()
            channel = null
        }

        fun restart() {
            stop()
            start()
            Toast.makeText(feiService, "restarted", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "KuangService"
        private const val foreground_notification_id = 10
    }
}

data class SseEvent(val data: String, val event: String? = null, val id: String? = null)
data class SharedFileInfo(val uri: Uri, val name: String)

private fun Application.configureRouting(feiService: FeiService) {
    routing {
        get("/") {
            call.respond(ThymeleafContent("index", mapOf("shares" to List(shares.value.size) { index ->
                index.toString()
            })))
        }

        get("/shares/{count}") {
            val s = call.parameters["count"]?.toInt() ?: return@get
            val info = shares.value[s]
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, info.name)
                    .toString()
            )
            val file = info.uri
            if (file.scheme == "file") {
                call.respondFile(file.toFile())
            } else call.respondUri(feiService, file)
        }
    }
}

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