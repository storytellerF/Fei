package com.storyteller_f.fei

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.storyteller_f.fei.service.FeiService
import com.storyteller_f.fei.service.ServerState
import com.storyteller_f.fei.service.SharedFileInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
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
        useService { service ->
            val server = service.server
            assertTrue(server.state.value is ServerState.Init)

            runBlocking {
                server.onReceiveEventPort(FeiService.DEFAULT_PORT)
            }
            assertTrue(server.state.value is ServerState.Started)
        }
    }

    @Test
    fun testConflictPort() {
        useService { service ->
            val engine = embeddedServer(
                Netty,
                port = FeiService.DEFAULT_PORT,
                host = FeiService.LISTENER_ADDRESS
            ) {
            }.start(wait = false)
            try {
                val server = service.server
                assertTrue(server.state.value is ServerState.Init)

                runBlocking {
                    server.onReceiveEventPort(FeiService.DEFAULT_PORT)
                }
                assertTrue(server.state.value is ServerState.Error)
            } finally {
                engine.stop()
            }

        }
    }

    @Test
    fun testLogin() {
        useClient { httpClient, feiService, cookie ->
            runBlocking {
                uriFilePath = File(feiService.filesDir, "list.txt").absolutePath
                savedUriFile.appendText("file:///test.zip")
                feiService.cacheInvalid()//when test
                val response =
                    httpClient.get("http://${FeiService.LISTENER_ADDRESS}:${FeiService.DEFAULT_PORT}/shares") {
                        headers {
                            append("cookie", cookie)
                        }
                    }
                assertTrue(response.status.isSuccess())
                val bodyAsText = response.bodyAsText()
                assertTrue(bodyAsText.contains("test.zip"))
            }
        }
    }

    private fun useService(block: (FeiService) -> Unit) {
        val serviceIntent = Intent(
            ApplicationProvider.getApplicationContext(),
            FeiService::class.java
        )
        val binder = serviceRule.bindService(serviceIntent)
        try {
            block((binder as FeiService.Fei).feiService)
        } finally {
            serviceRule.unbindService()
        }
    }

    private fun useClient(block: (HttpClient, FeiService, String) -> Unit) {
        useService { service ->
            val server = service.server
            HttpClient(CIO) {
                install(Logging)
            }.use {
                runBlocking {
                    server.onReceiveEventPort(FeiService.DEFAULT_PORT)
                    val cookie = it.submitForm(
                        "http://${FeiService.LISTENER_ADDRESS}:${FeiService.DEFAULT_PORT}/login",
                        formParameters = parameters {
                            append("user", "hidden")
                            append("password", "abc")
                        }).headers["set-cookie"]!!
                    block(it, service, cookie)
                }
            }
        }
    }
}