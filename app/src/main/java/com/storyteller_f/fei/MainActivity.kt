package com.storyteller_f.fei

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.storyteller_f.fei.service.FeiService
import com.storyteller_f.fei.service.SharedFileInfo
import com.storyteller_f.fei.service.SseEvent
import com.storyteller_f.fei.service.portFlow
import com.storyteller_f.fei.ui.components.*
import com.storyteller_f.fei.ui.theme.FeiTheme
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import kotlin.concurrent.thread

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

sealed class HidState {
    object BluetoothOff : HidState()
    object NoPermission : HidState()
    object NoBond : HidState()
    class Done(val device: ComposeBluetoothDevice) : HidState()
}

class MainActivity : ComponentActivity() {
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            addUri(uri)
        }

    private var bluetoothPermissionIndex by mutableStateOf(0)
    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        bluetoothPermissionIndex = bluetoothPermissionIndex.inc()
    }
    var selectedDevice by mutableStateOf<BluetoothDevice?>(null)

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
                fei?.feiService?.server?.channel?.trySend(SseEvent(data = "refresh"))
            }
        }
        val saveToLocal: (SharedFileInfo) -> Unit = {
            val uri = Uri.parse(it.uri)
            assert(uri.scheme != "file")
            fei?.saveToLocal(uri, it)
        }
        val requestPermission: () -> Unit = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                request.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                request.launch(Manifest.permission.BLUETOOTH)
            }
        }
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bluetoothManager.adapter.getProfileProxy(
                this,
                bluetoothConnection,
                BluetoothProfile.HID_DEVICE
            )
        }
        var bluetoothState by mutableStateOf(bluetoothManager.adapter.isEnabled)
        var bondDevices by mutableStateOf(
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) setOf<BluetoothDevice>(*bluetoothManager.adapter.bondedDevices.toTypedArray()) else setOf()
        )
        val bluetoothStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val intExtra = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0)
                        Log.i(TAG, "onReceive: $intExtra")
                        bluetoothState = intExtra == BluetoothAdapter.STATE_ON
                    }

                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null && isBonded(device)) {
                            bondDevices = bondDevices + device
                        }
                    }
                }
            }
        }
        val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_FOUND)
        }
        registerReceiver(bluetoothStateReceiver, intentFilter)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                super.onDestroy(owner)
                unregisterReceiver(bluetoothStateReceiver)
                unRegister(this@MainActivity, hidDevice)
            }
        })
        val connectDevice: (String) -> Boolean = { address ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val result = hidDevice?.connect(bondDevices.firstOrNull {
                    it.address == address
                }) ?: false
                Log.d(TAG, "onCreate: connect $address $result")
                result
            } else false
        }

        setContent {
            val context = LocalContext.current
            val state by produceState<HidState>(
                initialValue = HidState.BluetoothOff,
                bluetoothPermissionIndex,
                bluetoothState,
                selectedDevice
            ) {
                val local = selectedDevice
                value = when {
                    !bluetoothState -> HidState.BluetoothOff
                    !context.permissionOk() -> HidState.NoPermission
                    local == null -> HidState.NoBond
                    else -> HidState.Done(local.composeBluetoothDevice())
                }
            }
            val port by LocalContext.current.portFlow.collectAsState(initial = FeiService.defaultPort)
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
                val hid = hidDevice
                val device = selectedDevice
                if (hid != null && device != null) {
                    it.toKeyCode { code ->
                        sendReport(this@MainActivity, hid, device, code.second, code.first)
                    }
                } else {
                    navController.navigate("hid")
                }
            }
            FeiTheme {
                ModalNavigationDrawer(drawerContent = {
                    ModalDrawerSheet {
                        Spacer(Modifier.height(12.dp))
                        NavDrawer({
                            scope.launch {
                                drawerState.close()
                            }
                        }, {
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
                            port.toString(),
                            { fei?.restart() },
                            { fei?.stop() },
                            sendText
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
                                    Info(i ?: 0, port.toString(), sendText)
                                }
                                composable("settings") {
                                    SettingPage(port.toString())
                                }
                                composable("messages") {
                                    Messages()
                                }
                                composable("hid") {
                                    HidScreen(state, requestPermission, bondDevices.map {
                                        it.composeBluetoothDevice()
                                    }, connectDevice, sendText)
                                }
                            }
                        }
                    }
                }

            }
        }
        val intent = Intent(this, FeiService::class.java)
        startService(intent)
        if (fei == null) bindService(intent, connection, 0)
        CustomTabsClient.bindCustomTabsService(this, customTabPackageName, chromeConnection)
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.composeBluetoothDevice() =
        ComposeBluetoothDevice(name, address)

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection)
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

    private fun addUri(uri: Uri?) {
        uri ?: return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lifecycleScope.launch {
            saveUri(uri)
        }

    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private suspend fun saveUri(uri: Uri) {
        savedUriFile.appendText(uri.toString())
        cacheInvalid()
        fei?.feiService?.server?.channel?.send(SseEvent("refresh"))
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
    var hidDevice: BluetoothHidDevice? = null

    @RequiresApi(Build.VERSION_CODES.P)
    val bluetoothConnection = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(p0: Int, p1: BluetoothProfile?) {
            if (p0 == BluetoothProfile.HID_DEVICE && p1 != null) {
                val bluetoothHidDevice = p1 as BluetoothHidDevice
                hidDevice = bluetoothHidDevice
                if (!hidRegistered) {
                    Log.d(TAG, "onServiceConnected: 没有连接过蓝牙，开始连接")
                    registerApp(bluetoothHidDevice, registerCallback)
                }
            }
        }

        override fun onServiceDisconnected(p0: Int) {
        }
    }

    var hidRegistered: Boolean = false

    @RequiresApi(Build.VERSION_CODES.P)
    val registerCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            hidRegistered = registered
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            selectedDevice = if (state == BluetoothProfile.STATE_CONNECTED) {
                device
            } else {
                null
            }
        }
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
        val Descriptor = byteArrayOf(
            0x05.toByte(),
            0x01.toByte(),
            0x09.toByte(),
            0x02.toByte(),
            0xa1.toByte(),
            0x01.toByte(),
            0x09.toByte(),
            0x01.toByte(),
            0xa1.toByte(),
            0x00.toByte(),
            0x85.toByte(),
            0x01.toByte(),
            0x05.toByte(),
            0x09.toByte(),
            0x19.toByte(),
            0x01.toByte(),
            0x29.toByte(),
            0x03.toByte(),
            0x15.toByte(),
            0x00.toByte(),
            0x25.toByte(),
            0x01.toByte(),
            0x95.toByte(),
            0x03.toByte(),
            0x75.toByte(),
            0x01.toByte(),
            0x81.toByte(),
            0x02.toByte(),
            0x95.toByte(),
            0x01.toByte(),
            0x75.toByte(),
            0x05.toByte(),
            0x81.toByte(),
            0x03.toByte(),
            0x05.toByte(),
            0x01.toByte(),
            0x09.toByte(),
            0x30.toByte(),
            0x09.toByte(),
            0x31.toByte(),
            0x09.toByte(),
            0x38.toByte(),
            0x15.toByte(),
            0x81.toByte(),
            0x25.toByte(),
            0x7f.toByte(),
            0x75.toByte(),
            0x08.toByte(),
            0x95.toByte(),
            0x03.toByte(),
            0x81.toByte(),
            0x06.toByte(),
            0xc0.toByte(),
            0xc0.toByte(),
            0x05.toByte(),
            0x01.toByte(),
            0x09.toByte(),
            0x06.toByte(),
            0xa1.toByte(),
            0x01.toByte(),
            0x85.toByte(),
            0x02.toByte(),
            0x05.toByte(),
            0x07.toByte(),
            0x19.toByte(),
            0xE0.toByte(),
            0x29.toByte(),
            0xE7.toByte(),
            0x15.toByte(),
            0x00.toByte(),
            0x25.toByte(),
            0x01.toByte(),
            0x75.toByte(),
            0x01.toByte(),
            0x95.toByte(),
            0x08.toByte(),
            0x81.toByte(),
            0x02.toByte(),
            0x95.toByte(),
            0x01.toByte(),
            0x75.toByte(),
            0x08.toByte(),
            0x15.toByte(),
            0x00.toByte(),
            0x25.toByte(),
            0x65.toByte(),
            0x19.toByte(),
            0x00.toByte(),
            0x29.toByte(),
            0x65.toByte(),
            0x81.toByte(),
            0x00.toByte(),
            0x05.toByte(),
            0x08.toByte(),
            0x95.toByte(),
            0x05.toByte(),
            0x75.toByte(),
            0x01.toByte(),
            0x19.toByte(),
            0x01.toByte(),
            0x29.toByte(),
            0x05.toByte(),
            0x91.toByte(),
            0x02.toByte(),
            0x95.toByte(),
            0x01.toByte(),
            0x75.toByte(),
            0x03.toByte(),
            0x91.toByte(),
            0x03.toByte(),
            0xc0.toByte()
        )
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