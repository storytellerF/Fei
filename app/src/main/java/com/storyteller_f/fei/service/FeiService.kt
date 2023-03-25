package com.storyteller_f.fei.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import com.storyteller_f.fei.R
import com.storyteller_f.fei.dataStore
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
import io.ktor.websocket.*
import io.ktor.websocket.serialization.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.*

val Context.portFlow
    get() = dataStore.data.map {
        it[stringPreferencesKey("port")]?.toInt() ?: FeiService.defaultPort
    }

class FeiService : Service() {
    private val job = Job()
    val scope = CoroutineScope(Dispatchers.IO + job)
    val server = FeiServer(this)
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind() called with: intent = $intent")
        return Fei(this)
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
                server.port = it
                server.restartAsync()
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
        server.stopAsync()
        scope.cancel()
    }

    class Fei(val feiService: FeiService) : Binder() {
        fun sendMessage(it: String) {
            feiService.server.sendMessage(it)
        }

        fun saveToLocal(uri: Uri?, it: SharedFileInfo) {
            feiService.server.saveToLocal(uri, it)
        }

        fun restart() {
            feiService.server.restart()
        }

        fun stop() {
            feiService.server.stop()
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