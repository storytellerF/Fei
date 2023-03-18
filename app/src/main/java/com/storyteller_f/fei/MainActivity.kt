package com.storyteller_f.fei

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.storyteller_f.fei.ui.components.*
import com.storyteller_f.fei.ui.theme.FeiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import kotlin.concurrent.thread

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            addUri(uri)
        }

    @OptIn(
        ExperimentalMaterial3Api::class, ObsoleteCoroutinesApi::class
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val deleteItem: (SharedFileInfo) -> Unit = { path ->
            val uri = Uri.parse(path.uri)
            lifecycleScope.launch {
                if (uri.scheme == "file") {
                    uri.path?.let { File(it).delete() }
                } else {
                    removeUri(path)
                }
                cacheInvalid()
                fei?.channel?.trySend(SseEvent(data = "refresh"))
            }
        }
        val saveToLocal: (SharedFileInfo) -> Unit = {
            val uri = Uri.parse(it.uri)
            assert(uri.scheme != "file")
            fei?.saveToLocal(uri, it)
        }
        setContent {
            val port by LocalContext.current.portFlow.collectAsState(initial = FeiService.defaultPort)
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val navController = rememberNavController()
            MainContent(navController, drawerState, port.toString(), deleteItem, saveToLocal)
        }
        val intent = Intent(this, FeiService::class.java)
        startService(intent)
        if (fei == null) bindService(intent, connection, 0)
        CustomTabsClient.bindCustomTabsService(this, customTabPackageName, chromeConnection)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection)
        unbindService(chromeConnection)
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun MainContent(
        navController: NavHostController,
        drawerState: DrawerState,
        port: String,
        deleteItem: (SharedFileInfo) -> Unit,
        saveToLocal: (SharedFileInfo) -> Unit
    ) {
        val scope = rememberCoroutineScope()
        val snackBarHostState = remember { SnackbarHostState() }
        val infoList by shares.collectAsState()
        val density = LocalDensity.current
        val current = LocalContext.current
        val configuration = LocalConfiguration.current
        val screenHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }
        val currentBackStackEntryAsState by navController.currentBackStackEntryAsState()
        FeiTheme {
            ModalNavigationDrawer(drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(12.dp))
                    NavDrawer({
                        scope.launch {
                            drawerState.close()
                        }
                    } ,{
                        navController.navigate(it)
                    }, {
                        val builder = CustomTabsIntent.Builder()
                            .setInitialActivityHeightPx((screenHeight * 0.7).toInt())
                        val session = newSession
                        if (session != null) builder.setSession(session)
                        val customTabsIntent = builder.build()
                        customTabsIntent.launchUrl(current, Uri.parse(projectUrl))
                    })
                }
            }, drawerState = drawerState) {
                Scaffold(topBar = {
                    FeiMainToolbar(
                        port,
                        { fei?.restart() },
                        { fei?.stop() }
                    ) {
                        scope.launch {
                            drawerState.open()
                        }
                    }
                }, floatingActionButton = {
                    val text = currentBackStackEntryAsState?.destination?.route.orEmpty()
                    if (text != "messages")
                        FloatingActionButton(onClick = {
                            pickFile.launch(arrayOf("*/*"))
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = "add file")
                        }
                }, snackbarHost = {
                    SnackbarHost(hostState = snackBarHostState) {

                    }
                }) { paddingValues ->
                    // A surface container using the 'background' color from the theme
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        NavHost(navController = navController, startDestination = "main") {
                            composable("main") {
                                Main(infoList, deleteItem, saveToLocal) {
                                    val i = shares.value.indexOf(it)
                                    navController.navigate("info/$i")
                                }
                            }
                            composable("info/{index}", arguments = listOf(navArgument("index") {
                                type = NavType.IntType
                            })) {
                                val i = it.arguments?.getInt("index")
                                Info(i ?: 0, port)
                            }
                            composable("settings") {
                                SettingPage(port)
                            }
                            composable("messages") {
                                Messages()
                            }
                        }
                    }
                }
            }

        }
    }

    @Composable
    fun Messages() {
        val fei = fei
        if (fei != null) {
            val collectAsState by fei.messagesCache.collectAsState()
            MessagePage(collectAsState) {
                fei.sendMessage(it)
            }
        }
    }

    private fun addUri(uri: Uri?) {
        uri ?: return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lifecycleScope.launch {
            saveUri(uri)
        }

    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private suspend fun saveUri(uri: Uri) {
        val file = savedUriFile()
        withContext(Dispatchers.IO) {
            file.appendText("\n$uri")
        }
        cacheInvalid()
        fei?.channel?.send(SseEvent("refresh"))
    }

    var fei: FeiService.Fei? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.service_connected),
                Toast.LENGTH_SHORT
            ).show()
            val feiLocal = service as FeiService.Fei
            Log.i(TAG, "onServiceConnected: $feiLocal")
            fei = feiLocal
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fei = null
            Toast.makeText(
                this@MainActivity,
                getString(R.string.service_closed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val customTabPackageName = "com.android.chrome" // Change when in stable
    var newSession: CustomTabsSession? = null
    private val chromeConnection: CustomTabsServiceConnection =
        object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(
                name: ComponentName,
                client: CustomTabsClient
            ) {
                thread(name = "warmup chrome tab") {
                    val warmup = client.warmup(0)
                    Log.i(TAG, "onCustomTabsServiceConnected: warmup $warmup")
                    newSession = client.newSession(object : CustomTabsCallback() {

                    })
                    newSession?.mayLaunchUrl(
                        Uri.parse(projectUrl),
                        null,
                        null
                    )
                }

            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }


    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            cacheInvalid()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val projectUrl = "https://github.com/storytellerF/Fei"
    }
}

@Composable
fun Info(i: Int, port: String) {
    val t by produceState(initialValue = SharedFileInfo("", ""), i, shares) {
        value = shares.value[i]
    }

    Column {
        SharedFile(info = t)
        ShowQrCode("shares/$i", port, Modifier.padding(top = 20.dp))
    }

}