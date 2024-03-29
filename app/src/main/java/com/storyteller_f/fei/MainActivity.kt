package com.storyteller_f.fei

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.storyteller_f.fei.service.FeiService
import com.storyteller_f.fei.service.SharedFileInfo
import com.storyteller_f.fei.service.SseEvent
import com.storyteller_f.fei.service.portFlow
import com.storyteller_f.fei.ui.components.ComposeBluetoothDevice
import com.storyteller_f.fei.ui.components.FeiMainToolbar
import com.storyteller_f.fei.ui.components.HidScreen
import com.storyteller_f.fei.ui.components.Main
import com.storyteller_f.fei.ui.components.MessagePage
import com.storyteller_f.fei.ui.components.NavDrawer
import com.storyteller_f.fei.ui.components.SafePage
import com.storyteller_f.fei.ui.components.SettingPage
import com.storyteller_f.fei.ui.components.SharedFile
import com.storyteller_f.fei.ui.components.ShowQrCode
import com.storyteller_f.fei.ui.theme.FeiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

sealed class HidState {
    object NotSupport : HidState()
    object BluetoothOff : HidState()
    object NoPermission : HidState()
    class NoBond(val bondDevices: List<ComposeBluetoothDevice>, val connecting: String? = null) :
        HidState()

    class Done(val device: ComposeBluetoothDevice) : HidState()
}

class MainActivity : ComponentActivity() {
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uri ->
            addUri(uri)
        }

    private val request =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) bf.permissionChanged()
        }
    private val bf by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            BluetoothFei(this)
        } else {
            NoOpBluetoothFei()
        }
    }
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            fei?.feiService?.onUserGrantNotificationPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        bf.start()
        if (!isUriFilePathInitialised) {
            uriFilePath = File(filesDir, "list.txt").absolutePath
        }
        setContent {
            val state by bf.state()
            val port by LocalContext.current.portFlow.collectAsState(initial = FeiService.DEFAULT_PORT)
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val navController = rememberNavController()
            val scope = rememberCoroutineScope()
            val snackBarHostState = remember { SnackbarHostState() }
            val infoList by shares.collectAsState()
            val density = LocalDensity.current
            val current = LocalContext.current
            val configuration = LocalConfiguration.current
            val screenHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }
            val currentBackStackEntryAsState by navController.currentBackStackEntryAsState()

            val sendText: (String) -> Unit = {
                scope.launch {
                    if (!bf.sendText(it)) {
                        Toast.makeText(current, "未连接到任何设备", Toast.LENGTH_SHORT).show()
                        navController.navigate("hid")
                    }
                }
            }
            val showAboutWebView = {
                val builder = CustomTabsIntent.Builder()
                    .setInitialActivityHeightPx((screenHeight * 0.7).toInt())
                val session = newSession
                if (session != null) builder.setSession(session)
                val customTabsIntent = builder.build()
                customTabsIntent.launchUrl(current, Uri.parse(projectUrl))
            }

            FeiTheme {
                ModalNavigationDrawer(drawerContent = {
                    Drawer(scope, drawerState, navController, showAboutWebView)
                }, drawerState = drawerState) {
                    Scaffold(topBar = {
                        TopBar(port, sendText, scope, drawerState)
                    }, floatingActionButton = {
                        Floating(currentBackStackEntryAsState)
                    }, snackbarHost = {
                        SnackbarHost(hostState = snackBarHostState) { }
                    }) { paddingValues ->
                        MainContent(paddingValues, navController, infoList, port, sendText, state)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    NotificationRequest()
                }

            }
        }
        val intent = Intent(this, FeiService::class.java)
        startService(intent)
        if (fei == null) bindService(intent, feiServiceConnection, 0)
        CustomTabsClient.bindCustomTabsService(this, customTabPackageName, chromeConnection)

    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Composable
    @OptIn(ExperimentalPermissionsApi::class)
    private fun NotificationRequest() {
        var showRationaleDialog by remember {
            mutableStateOf(false)
        }
        val postNotificationPermission =
            rememberPermissionState(permission = Manifest.permission.POST_NOTIFICATIONS)
        LaunchedEffect(key1 = postNotificationPermission) {
            if (!postNotificationPermission.status.isGranted) {
                if (postNotificationPermission.status.shouldShowRationale) {
                    showRationaleDialog = true
                } else
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (showRationaleDialog) {
            AlertDialog(onDismissRequest = {
                showRationaleDialog = false
            }, confirmButton = {
                showRationaleDialog = false
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, text = {
                Text(text = "为了能够保证在后台正常运行，需要”Post Notification“ 权限")
            })
        }
    }

    @Composable
    private fun MainContent(
        paddingValues: PaddingValues,
        navController: NavHostController,
        infoList: List<SharedFileInfo>,
        port: Int,
        sendText: (String) -> Unit,
        state: HidState
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            NavHost(navController = navController, startDestination = "main") {
                navPages(
                    infoList,
                    navController,
                    port,
                    state,
                    ::saveToLocal,
                    ::deleteItem,
                    sendText,
                    ::requestPermission
                )
            }
        }
    }

    @Composable
    private fun Floating(currentBackStackEntryAsState: NavBackStackEntry?) {
        val text = currentBackStackEntryAsState?.destination?.route.orEmpty()
        if (text != "messages")
            FloatingActionButton(onClick = {
                pickFile.launch(arrayOf("*/*"))
            }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = getString(R.string.add_file)
                )
            }
    }

    @Composable
    private fun Drawer(
        scope: CoroutineScope,
        drawerState: DrawerState,
        navController: NavHostController,
        showAboutWebView: () -> Unit
    ) {
        ModalDrawerSheet {
            Spacer(Modifier.height(12.dp))
            NavDrawer({
                scope.launch {
                    drawerState.close()
                }
            }, {
                navController.navigate(it)
            }, showAboutWebView)
        }
    }

    @Composable
    private fun TopBar(
        port: Int,
        sendText: (String) -> Unit,
        scope: CoroutineScope,
        drawerState: DrawerState
    ) {
        FeiMainToolbar(
            port.toString(),
            { fei?.restart() },
            { fei?.stop() },
            sendText,
            {
                scope.launch {
                    drawerState.open()
                }
            },
            {
                shares.value.forEach(::deleteItem)
            }
        )
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            request.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            request.launch(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    private fun saveToLocal(it: SharedFileInfo) {
        val uri = Uri.parse(it.uri)
        assert(uri.scheme != "file")
        fei?.saveToLocal(uri, it)
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private fun deleteItem(path: SharedFileInfo) {
        val uri = Uri.parse(path.uri)
        lifecycleScope.launch {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                removeUri(path)
            }
            cacheInvalid()//when delete
            serverChannel?.trySend(SseEvent(data = "refresh"))
        }
    }

    private fun NavGraphBuilder.navPages(
        infoList: List<SharedFileInfo>,
        navController: NavHostController,
        port: Int,
        state: HidState,
        saveToLocal: (SharedFileInfo) -> Unit,
        deleteItem: (SharedFileInfo) -> Unit,
        sendText: (String) -> Unit,
        requestPermission: () -> Unit
    ) {
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
            Info(i ?: 0, port.toString(), sendText)
        }
        composable("settings") {
            SettingPage(port.toString())
        }
        composable("messages") {
            Messages()
        }
        composable("hid") {
            HidScreen(state, requestPermission, {
                bf.connectDevice(it)
            }, sendText) {
                bf.disconnectDevice(it)
            }
        }
        composable("safe") {
            SafePage()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(feiServiceConnection)
        unbindService(chromeConnection)
    }

    @Composable
    fun Messages() {
        val fei = fei
        if (fei != null) {
            val collectAsState by fei.feiService.server.messagesCache.collectAsState()
            MessagePage(collectAsState) {
                fei.sendMessage(it)
            }
        }
    }

    private fun addUri(uri: List<Uri>) {
        lifecycleScope.launch {
            uri.forEach {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                saveUri(it)
            }
        }

    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private suspend fun saveUri(uri: Uri) {
        savedUriFile.appendText(uri.toString())
        cacheInvalid()//when save
        serverChannel?.send(SseEvent("refresh"))
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private val serverChannel get() = fei?.feiService?.server?.channel

    var fei: FeiService.Fei? = null
    private val feiServiceConnection = object : ServiceConnection {
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
            cacheInvalid()//when resume
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val projectUrl = "https://github.com/storytellerF/Fei"
    }
}

@Composable
fun Info(i: Int, port: String, sendText: (String) -> Unit) {
    val t by produceState(initialValue = SharedFileInfo("", ""), i, shares) {
        value = shares.value[i]
    }

    Column {
        SharedFile(info = t)
        ShowQrCode("shares/$i", port, Modifier.padding(top = 20.dp), sendText)
    }

}