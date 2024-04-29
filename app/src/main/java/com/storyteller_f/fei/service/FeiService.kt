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
import com.storyteller_f.fei.cacheInvalid
import com.storyteller_f.fei.dataStore
import com.storyteller_f.fei.removeUri
import com.storyteller_f.fei.saveFile
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

val Context.portFlow
    get() = dataStore.data.map {
        it[stringPreferencesKey("port")]?.toInt() ?: FeiService.DEFAULT_PORT
    }

class FeiService : Service() {
    private val job = Job()
    val scope = CoroutineScope(Dispatchers.IO + job)
    val server = FeiServer(this)
    private val specialEventPort = MutableStateFlow<Int?>(null)

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
        val channelId = FOREGROUND_CHANNEL_ID
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
            combine(portFlow, specialEventPort) { port, eventPort ->
                eventPort ?: port
            }.collect {
                Log.i(TAG, "onCreate: port $it")
                server.onReceiveEventPort(it)
            }
        }
        scope.launch {
            server.state.filterIsInstance<ServerState.Error>().collectLatest {
                postNotify(it.cause.message ?: "error: ${it.cause::class.qualifiedName}")
            }
        }

    }

    private fun postNotify(managerCompat: NotificationManagerCompat, message: String) {
        if (managerCompat.areNotificationsEnabled()) {
            val notification =
                NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(getString(R.string.app_name)).setContentText(message).build()
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun postNotify(message: String) {
        postNotify(NotificationManagerCompat.from(this), message)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        super.onDestroy()
        server.stopBlocking()
        scope.cancel()
    }

    fun onUserGrantNotificationPermission() {
        postNotify("已同意权限")
    }

    fun saveToLocal(uri: Uri, info: SharedFileInfo) {
        scope.launch {
            saveFile(File(info.name).extension, uri)
            removeUri(info)
            cacheInvalid()//when save to local
            server.emitRefreshEvent()
        }
    }

    fun sendMessage(message: String) {
        scope.launch {
            server.sendMessage(message)
        }
    }

    fun restart() {
        specialEventPort.value = SPECIAL_PORT_RESTART
    }

    fun stop() {
        specialEventPort.value = SPECIAL_PORT_STOP
    }

    class Fei(val feiService: FeiService) : Binder() {
        fun sendMessage(it: String) {
            feiService.sendMessage(it)
        }

        fun saveToLocal(uri: Uri, it: SharedFileInfo) {
            feiService.saveToLocal(uri, it)
        }

        fun restart() {
            feiService.restart()
        }

        fun stop() {
            feiService.stop()
        }

    }

    companion object {
        private const val TAG = "FeiService"
        private const val FOREGROUND_NOTIFICATION_ID = 10
        private const val FOREGROUND_CHANNEL_ID = "foreground"
        const val DEFAULT_PORT = 8080
        const val LISTENER_ADDRESS = "0.0.0.0"
        const val DEFAULT_ADDRESS = "127.0.0.1"

        const val VALID_PORT = 1_000
        const val SPECIAL_PORT_STOP = -1
        const val SPECIAL_PORT_RESTART = -2
    }
}

data class SseEvent(val data: String, val event: String? = null, val id: String? = null)

@kotlinx.serialization.Serializable
data class SharedFileInfo(val uri: String, val name: String)

suspend fun ApplicationCall.respondSse(eventFlow: Flow<SseEvent>) {
    response.cacheControl(CacheControl.NoCache(null))
    respondBytesWriter(contentType = ContentType.Text.EventStream) {
        eventFlow.collect { event ->
            if (event.id != null) {
                writeStringUtf8("id: ${event.id}\n")
            }
            if (event.event != null) {
                writeStringUtf8("event: ${event.event}\n")
            }
            for (dataLine in event.data.lines()) {
                writeStringUtf8("data: $dataLine\n")
            }
            writeStringUtf8("\n")
            flush()
        }
    }
}