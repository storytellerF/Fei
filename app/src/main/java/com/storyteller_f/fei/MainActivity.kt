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
import com.storyteller_f.fei.service.Message
import com.storyteller_f.fei.service.ServerState
import com.storyteller_f.fei.service.SharedFileInfo
import com.storyteller_f.fei.service.portFlow
import com.storyteller_f.fei.ui.components.ComposeBluetoothDevice
import com.storyteller_f.fei.ui.components.FeiMainToolbar
import com.storyteller_f.fei.ui.components.HidScreen
import com.storyteller_f.fei.ui.components.SharedFiles
import com.storyteller_f.fei.ui.components.MessagePage
import com.storyteller_f.fei.ui.components.NavDrawer
import com.storyteller_f.fei.ui.components.SafePage
import com.storyteller_f.fei.ui.components.SettingPage
import com.storyteller_f.fei.ui.components.SharedFile
import com.storyteller_f.fei.ui.components.ShowQrCode
import com.storyteller_f.fei.ui.theme.FeiTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import kotlin.concurrent.thread


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

sealed class HidState {
    data object NotSupport : HidState()
    data object BluetoothOff : HidState()
    data object NoPermission : HidState()
    class NoBond(val bondDevices: List<ComposeBluetoothDevice>, val connecting: String? = null) :
        HidState()

    class Done(val device: ComposeBluetoothDevice) : HidState()
}


class MainActivity : ComponentActivity() {
    private val fei = MutableStateFlow<FeiService.Fei?>(null)
    private val currentFeiBinder get() = fei.value
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
            currentFeiBinder?.feiService?.onUserGrantNotificationPermission()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val feiServerState = fei.flatMapLatest {
        it?.feiService?.server?.state ?: MutableStateFlow(ServerState.Init)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val messageFlow = fei.flatMapLatest {
        it?.feiService?.server?.messagesCache ?: MutableStateFlow(
            listOf(
                Message(
                    "system",
                    "Server stopped."
                )
            )
        )
    }

    private val feiServiceConnection by lazy { HidConnection(this) }

    var newSession: CustomTabsSession? = null
    private val chromeConnection by lazy { CustomTabConnection(this) }

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
            val density = LocalDensity.current
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val screenHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }
            val currentBackStackEntryAsState by navController.currentBackStackEntryAsState()

            val sendText: (String) -> Unit = {
                scope.launch {
                    if (!bf.sendText(it)) {
                        Toast.makeText(context, "未连接到任何设备", Toast.LENGTH_SHORT).show()
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
                customTabsIntent.launchUrl(context, Uri.parse(PROJECT_URL))
            }

            FeiTheme {
                ModalNavigationDrawer(drawerContent = {
                    Drawer(showAboutWebView, {
                        scope.launch {
                            drawerState.close()
                        }
                    }) {
                        navController.navigate(it)
                    }
                }, drawerState = drawerState) {
                    Scaffold(topBar = {
                        TopBar(port, sendText) {
                            scope.launch {
                                drawerState.open()
                            }
                        }
                    }, floatingActionButton = {
                        Floating(currentBackStackEntryAsState?.destination?.route.orEmpty())
                    }, snackbarHost = {
                        SnackbarHost(hostState = snackBarHostState) { }
                    }) { paddingValues ->
                        MainContent(paddingValues, navController, port, sendText, state)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    NotificationRequest()
                }

            }
        }
        val intent = Intent(this, FeiService::class.java)
        startService(intent)
        if (currentFeiBinder == null) bindService(intent, feiServiceConnection, 0)
        CustomTabsClient.bindCustomTabsService(this, CUSTOM_TAB_PACKAGE_NAME, chromeConnection)
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
                    port,
                    state,
                    ::saveToLocal,
                    ::deleteItem,
                    sendText,
                    ::requestPermission
                ) { i -> navController.navigate("info/$i") }
            }
        }
    }

    @Composable
    private fun Floating(text: String) {
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
        showAboutWebView: () -> Unit,
        closeDrawer: () -> Unit,
        navigateTo: (String) -> Unit
    ) {
        ModalDrawerSheet {
            Spacer(Modifier.height(12.dp))
            NavDrawer({
                closeDrawer()
            }, navigateTo, showAboutWebView)
        }
    }

    @Composable
    private fun TopBar(
        port: Int,
        sendText: (String) -> Unit,
        openDrawer: () -> Unit
    ) {
        FeiMainToolbar(
            port.toString(),
            { currentFeiBinder?.restart() },
            { currentFeiBinder?.stop() },
            sendText,
            openDrawer,
            {
                shares.value.forEach(::deleteItem)
            },
            feiServerState
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
        currentFeiBinder?.saveToLocal(uri, it)
    }

    private fun deleteItem(info: SharedFileInfo) {
        val uri = Uri.parse(info.uri)
        currentFeiBinder?.deleteUri(uri, info)
    }

    private fun NavGraphBuilder.navPages(
        port: Int,
        state: HidState,
        saveToLocal: (SharedFileInfo) -> Unit,
        deleteItem: (SharedFileInfo) -> Unit,
        sendText: (String) -> Unit,
        requestPermission: () -> Unit,
        navigateToInfo: (Int) -> Unit
    ) {
        composable("main") {
            val infoList by shares.collectAsState()
            SharedFiles(infoList, deleteItem, saveToLocal) {
                val i = shares.value.indexOf(it)
                navigateToInfo(i)
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
        val messageList by messageFlow.collectAsState(initial = emptyList())
        MessagePage(messageList) {
            currentFeiBinder?.sendMessage(it)
        }
    }

    private fun addUri(uri: List<Uri>) {
        uri.forEach {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            currentFeiBinder?.appendUri(it)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            cacheInvalid()//when resume
        }
    }

    fun bindFei(feiBinder: FeiService.Fei) {
        fei.value = feiBinder
    }

    fun unbindFei() {
        fei.value = null
    }

    companion object {
        const val PROJECT_URL = "https://github.com/storytellerF/Fei"
        private const val CUSTOM_TAB_PACKAGE_NAME = "com.android.chrome" // Change when in stable
    }
}

class HidConnection(activity: MainActivity) : ServiceConnection {
    private val activityRef = WeakReference(activity)
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val activity = activityRef.get() ?: return
        Toast.makeText(
            activity,
            activity.getString(R.string.service_connected),
            Toast.LENGTH_SHORT
        ).show()
        val fei = service as FeiService.Fei
        Log.i("HidConnection", "onServiceConnected: $fei")
        activity.bindFei(fei)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        val activity = activityRef.get() ?: return
        activity.unbindFei()
        Toast.makeText(
            activity,
            activity.getString(R.string.service_closed),
            Toast.LENGTH_SHORT
        ).show()
    }
}

class CustomTabConnection(activity: MainActivity) : CustomTabsServiceConnection() {
    private val activityRef = WeakReference(activity)
    override fun onCustomTabsServiceConnected(
        name: ComponentName,
        client: CustomTabsClient
    ) {
        thread(name = "warmup chrome tab") {
            val warmup = client.warmup(0)
            Log.i("CustomTabConnection", "onCustomTabsServiceConnected: warmup $warmup")
            val newSession = client.newSession(object : CustomTabsCallback() {

            })
            activityRef.get()?.newSession = newSession
            newSession?.mayLaunchUrl(
                Uri.parse(MainActivity.PROJECT_URL),
                null,
                null
            )
        }

    }

    override fun onServiceDisconnected(name: ComponentName) {}
}

@Composable
fun Info(i: Int, port: String, sendText: (String) -> Unit) {
    val currentShares by shares.collectAsState()

    Column {
        SharedFile(info = currentShares[i])
        ShowQrCode("shares/$i", port, Modifier.padding(top = 20.dp), sendText)
    }

}