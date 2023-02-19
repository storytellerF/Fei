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
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.File

class FeiService : Service() {
    private var binder: Fei? = null
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind() called with: intent = $intent")
        assert(binder == null) {
            "binder 重复创建"
        }
        return Fei(this).also {
            binder = it
        }
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
            setName("running")
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
        binder?.stop()
    }

    class Fei(feiService: FeiService) : Binder() {
        val path = feiService.filesDir
        private var server: ApplicationEngine? = null
        fun start() {
            Log.d(TAG, "start() called")
            try {

                this.server = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
                    plugPlugins()
                    configureRouting(path)
                }.start(wait = false)
            } catch (th: Throwable) {
                Log.e(TAG, "start: ${th.localizedMessage}", th)
            }

        }

        private fun Application.plugPlugins() {
            install(StatusPages) {
                exception<Throwable> { call: ApplicationCall, cause->
                    call.respondText(cause.localizedMessage, status = HttpStatusCode.InternalServerError)
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
        }

        fun restart(context: Context) {
            stop()
            start()
            Toast.makeText(context, "restarted", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "KuangService"
        private const val foreground_notification_id = 10
    }
}

data class SharedFile(val id: String, val uri: Uri)

private fun Application.configureRouting(path: File) {
    routing {
        get("/") {
            call.respond(ThymeleafContent("index", mapOf("shares" to "1")))
        }
        get("/{count}") {
            val s = call.parameters["count"]
            val file = File(path,"fb2087ed475c45aec97cf9730ee7f7cd.jpg")
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "$s.png")
                    .toString()
            )
            call.respondFile(file)
        }
    }
}
