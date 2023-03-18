package com.storyteller_f.fei

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.jamal.composeprefs3.ui.PrefsScreen
import com.jamal.composeprefs3.ui.prefs.EditTextPref
import com.storyteller_f.fei.ui.components.MessagePage
import com.storyteller_f.fei.ui.components.ShowQrCode
import com.storyteller_f.fei.ui.theme.FeiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.stream.IntStream
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
        val snackBarHostState = remember { SnackbarHostState() }
        val currentBackStackEntryAsState by navController.currentBackStackEntryAsState()
        FeiTheme {
            ModalNavigationDrawer(drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(12.dp))
                    NavDrawer(navController, drawerState)
                }
            }, drawerState = drawerState) {
                Scaffold(topBar = {
                    FeiMainToolbar(
                        port,
                        drawerState,
                        { fei?.restart() },
                        { fei?.stop() })
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
                                Main(shares, deleteItem, saveToLocal) {
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

    @Composable
    @OptIn(ExperimentalComposeUiApi::class)
    private fun SettingPage(port: String) {
        PrefsScreen(dataStore = LocalContext.current.dataStore) {
            prefsItem {
                EditTextPref(
                    key = "port",
                    title = stringResource(R.string.port),
                    summary = "server listen on $port",
                    dialogTitle = stringResource(R.string.port_setting),
                    dialogMessage = stringResource(R.string.please_input_a_valid_port),
                    defaultValue = FeiService.defaultPort.toString()
                )
            }
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
    private fun FeiMainToolbar(
        port: String,
        drawerState: DrawerState,
        restartService: () -> Unit,
        stopService: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var showDialog by rememberSaveable { mutableStateOf(false) }

        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_name))

                    Text(
                        text = port,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(8.dp)
                            .background(
                                MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp, 2.dp)
                            .clickable {
                                showDialog = true
                            }
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = {
                    scope.launch {
                        drawerState.open()
                    }
                }) {
                    Icon(Icons.Filled.Menu, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = {
                    restartService()
                }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.restart_service)
                    )
                }
                IconButton(onClick = {
                    stopService()
                }) {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.baseline_stop_24),
                        contentDescription = stringResource(R.string.stop_service)
                    )
                }
            },

            )
        if (showDialog)
            AlertDialog(
                onDismissRequest = { showDialog = false },
                confirmButton = {
                    Text(
                        text = stringResource(id = android.R.string.ok),
                        modifier = Modifier.clickable {
                            showDialog = false
                        })
                },
                text = {
                    ShowQrCode(sub = "", port = port)
                },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false
                ),
            )
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun NavDrawer(navController: NavHostController, drawerState: DrawerState) {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val screenHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }
        NavigationDrawerItem(
            label = {
                Text(text = stringResource(R.string.home))
            },
            icon = {
                Icon(Icons.Filled.Home, contentDescription = stringResource(id = R.string.home))
            },
            selected = false,
            onClick = {
                navController.navigate("main")
                scope.launch {
                    drawerState.close()
                }
            })

        NavigationDrawerItem(label = { Text(text = "messages") }, icon = {
            Icon(Icons.Filled.AccountBox, contentDescription = "messages")
        }, selected = false, onClick = {
            navController.navigate("messages")
            scope.launch {
                drawerState.close()
            }
        })

        NavigationDrawerItem(
            label = {
                Text(text = stringResource(R.string.about))
            },
            icon = {
                Icon(Icons.Filled.Info, contentDescription = stringResource(id = R.string.about))
            },
            selected = false,
            onClick = {
                val builder = CustomTabsIntent.Builder()
                    .setInitialActivityHeightPx((screenHeight * 0.7).toInt())
                val session = newSession
                if (session != null) builder.setSession(session)
                val customTabsIntent = builder.build()
                customTabsIntent.launchUrl(this, Uri.parse(projectUrl))
                scope.launch {
                    drawerState.close()
                }
            })
        NavigationDrawerItem(
            label = {
                Text(text = stringResource(R.string.settings))
            },
            icon = {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(id = R.string.settings)
                )
            },
            selected = false,
            onClick = {
                navController.navigate("settings")
                scope.launch {
                    drawerState.close()
                }
            })
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
fun Main(
    flow: MutableStateFlow<List<SharedFileInfo>>,
    deleteItem: (SharedFileInfo) -> Unit = {},
    saveToLocal: (SharedFileInfo) -> Unit = {},
    viewInfo: (SharedFileInfo) -> Unit = {},
) {
    val collectAsState by flow.collectAsState()

    LazyColumn(content = {
        items(collectAsState.size) {
            SharedFile(collectAsState[it], true, deleteItem, saveToLocal, viewInfo)
        }
    })
}

class ShareFilePreviewProvider : PreviewParameterProvider<SharedFileInfo> {
    override val values: Sequence<SharedFileInfo>
        get() = sequenceOf(SharedFileInfo("http://test.com", "world"))

}

@Preview
@Composable
private fun SharedFile(
    @PreviewParameter(ShareFilePreviewProvider::class) info: SharedFileInfo,
    allowView: Boolean = false,
    deleteItem: (SharedFileInfo) -> Unit = {},
    saveToLocal: (SharedFileInfo) -> Unit = {},
    viewInfo: (SharedFileInfo) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier
        .clickable {
            expanded = true
        }
        .fillMaxWidth()
        .padding(10.dp)) {
        Text(text = info.name, fontSize = 14.sp)
        Text(text = info.uri, fontSize = 10.sp)

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(text = {
                Text(text = stringResource(R.string.delete))
            }, onClick = {
                deleteItem(info)
            })
            val uri = Uri.parse(info.uri)
            if (uri.scheme != "file")
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.save_to_local)) },
                    onClick = {
                        saveToLocal(info)
                        expanded = false
                    })
            if (allowView)
                DropdownMenuItem(text = {
                    Text(text = stringResource(R.string.view))
                }, onClick = {
                    viewInfo(info)
                })
        }
    }

}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    FeiTheme {
        Main(MutableStateFlow(ShareFilePreviewProvider().values.toList()))
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

fun String.createQRImage(width: Int, height: Int): Bitmap {
    val bitMatrix = QRCodeWriter().encode(
        this,
        BarcodeFormat.QR_CODE,
        width,
        height,
        Collections.singletonMap(EncodeHintType.CHARACTER_SET, "utf-8")
    )
    return Bitmap.createBitmap(
        IntStream.range(0, height).flatMap { h: Int ->
            IntStream.range(0, width).map { w: Int ->
                if (bitMatrix[w, h]
                ) Color.BLACK else Color.WHITE
            }
        }.toArray(),
        width, height, Bitmap.Config.ARGB_8888
    )
}