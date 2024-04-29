package com.storyteller_f.fei

import android.content.Intent
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.storyteller_f.fei.service.FeiService
import com.storyteller_f.fei.service.ServerState
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import org.junit.Rule

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
        val serviceIntent = Intent(
            ApplicationProvider.getApplicationContext(),
            FeiService::class.java
        )

        // Bind the service and grab a reference to the binder.
        val binder: IBinder = serviceRule.bindService(serviceIntent)

        // Get the reference to the service, or you can call
        // public methods on the binder directly.
        val service = (binder as FeiService.Fei).feiService

        val server = service.server
        assertTrue(server.state.value is ServerState.Init)

        runBlocking {
            server.onReceiveEventPort(8080)
            assertTrue(server.state.value is ServerState.Started)
            serviceRule.unbindService()
        }
    }

    @Test
    fun testConflictPort() {
        val serviceIntent = Intent(
            ApplicationProvider.getApplicationContext(),
            FeiService::class.java
        )
        val engine = embeddedServer(Netty, port = 8080, host = FeiService.LISTENER_ADDRESS) {
        }.start(wait = false)

        // Bind the service and grab a reference to the binder.
        val binder: IBinder = serviceRule.bindService(serviceIntent)

        // Get the reference to the service, or you can call
        // public methods on the binder directly.
        val service = (binder as FeiService.Fei).feiService

        val server = service.server
        assertTrue(server.state.value is ServerState.Init)

        runBlocking {
            server.onReceiveEventPort(8080)
            assertTrue(server.state.value is ServerState.Error)
            serviceRule.unbindService()
            engine.stop()
        }
    }
}