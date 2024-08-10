package com.storyteller_f.fei

import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.storyteller_f.fei.service.FeiServer
import com.storyteller_f.fei.service.FeiService
import com.storyteller_f.fei.service.ServerState
import com.storyteller_f.fei.service.specialEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun testServerStart() {
        useService { _, _, server ->
            runBlocking {
                server.onReceiveEventPort(FeiService.DEFAULT_PORT, null)
            }
            assertTrue(server.state.value is ServerState.Started)
        }
    }

    @Test
    fun testServerStop() {
        useService { _, _, server ->
            runBlocking {
                server.onReceiveEventPort(FeiService.DEFAULT_PORT, null)
                assertTrue(server.state.value is ServerState.Started)
                server.onReceiveEventPort(0, FeiService.EVENT_STOP)
                assertTrue(server.state.value is ServerState.Stopped)
            }
        }
    }

    @Test
    fun testServerRestart() {
        useService { _, _, server ->
            runBlocking {
                server.onReceiveEventPort(FeiService.DEFAULT_PORT, null)
                val preStartedTime = (server.state.value as ServerState.Started).time
                server.onReceiveEventPort(FeiService.DEFAULT_PORT, FeiService.EVENT_RESTART)
                assertNotEquals(preStartedTime, (server.state.value as ServerState.Started).time)
            }
        }
    }

    @Test
    fun testConflictPort() {
        useService { _, _, server ->
            otherServer {
                runBlocking {
                    server.onReceiveEventPort(FeiService.DEFAULT_PORT, null)
                }
                assertTrue(server.state.value is ServerState.Error)
            }

        }
    }

    @Test
    fun testLogin() {
        useClient { fei, httpClient, _, _ ->
            fei.appendUri("file:///test.zip".toUri())
            val response =
                httpClient.get("http://${FeiService.LISTENER_ADDRESS}:${FeiService.DEFAULT_PORT}/shares")
            assertTrue(response.status.isSuccess())
            val bodyAsText = response.bodyAsText()
            assertTrue(bodyAsText.contains("test.zip"))
        }
    }

    @Test
    fun testSSE() {
        useSSE { fei, sseClient ->
            val deferred = async {
                val session = sseClient.sseSession(urlString = "http://${FeiService.LISTENER_ADDRESS}:${FeiService.DEFAULT_PORT}/sse")
                try {
                    session.incoming.first {
                        it.data == "refresh"
                    }
                    session.cancel()
                } catch (e: Exception) {
                    session.cancel()
                }
            }
            launch {
                delay(1000)
                fei.appendUri("file:///test.zip".toUri())
            }
            deferred.await()
        }
    }

    private fun useSSE(block: suspend CoroutineScope.(FeiService.Fei, sseClient: HttpClient) -> Unit) {
        useClient { fei, _, _, cookie ->
            HttpClient {
                install(SSE) {
                    showCommentEvents()
                    showRetryEvents()
                }
                install(Logging)
                defaultRequest {
                    headers {
                        append("cookie", cookie)
                    }
                }
            }.use {
                block(fei, it)
            }
        }
    }

    private fun useService(block: (FeiService.Fei, FeiService, FeiServer) -> Unit) {
        val serviceIntent = Intent(
            ApplicationProvider.getApplicationContext(),
            FeiService::class.java
        )
        specialEvent.value = FeiService.EVENT_OFF
        try {
            val binder = serviceRule.bindService(serviceIntent)
            val fei = binder as FeiService.Fei
            val feiService = fei.feiService
            block(fei, feiService, feiService.server)
        } finally {
            serviceRule.unbindService()
        }
    }

    private fun useClient(block: suspend CoroutineScope.(FeiService.Fei, HttpClient, FeiService, cookie: String) -> Unit) {
        useService { fei, service, server ->
            uriFilePath = File(service.filesDir, "list.txt").absolutePath
            val cookieMap = mutableMapOf<String, String>()
            HttpClient(CIO) {
                install(Logging)
                defaultRequest {
                    headers {
                        cookieMap.forEach(::append)
                    }
                }
            }.use {
                runBlocking {
                    server.onReceiveEventPort(FeiService.DEFAULT_PORT, null)
                    val cookie = it.submitForm(
                        "http://${FeiService.LISTENER_ADDRESS}:${FeiService.DEFAULT_PORT}/login",
                        formParameters = parameters {
                            append("user", "hidden")
                            append("password", "")
                        }).headers["set-cookie"]!!
                    cookieMap["cookie"] = cookie
                    block(fei, it, service, cookie)
                }
            }
        }
    }

    private fun otherServer(block: () -> Unit) {
        val engine = embeddedServer(
            Netty,
            port = FeiService.DEFAULT_PORT,
            host = FeiService.LISTENER_ADDRESS
        ) {
        }.start(wait = false)
        try {
            block()
        } finally {
            engine.stop()
        }
    }

}