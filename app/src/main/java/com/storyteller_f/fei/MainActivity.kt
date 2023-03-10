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
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color.Companion.LightGray
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.jamal.composeprefs3.ui.PrefsScreen
import com.jamal.composeprefs3.ui.prefs.EditTextPref
import com.storyteller_f.fei.ui.theme.FeiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.stream.IntStream

val shares = MutableStateFlow<List<SharedFileInfo>>(listOf())

private fun Context.savedUriFile(): File {
    return File(filesDir, "list.txt")
}

fun Context.cacheInvalid() {
    val listFile = savedUriFile()
    if (!listFile.exists()) listFile.createNewFile()
    val readText = listFile.readText()

    val list = readText.split("\n").filter {
        it.isNotEmpty()
    }.mapNotNull {
        val toUri = it.toUri()
        try {
            val name = contentResolver.query(toUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val string = cursor.getString(columnIndex)
                    string
                } else "unknown"
            } ?: "unknown"
            SharedFileInfo(it, name)
        } catch (e: Exception) {
            null
        }
    }
    listFile.writeText(list.joinToString("\n") {
        it.uri
    })
    val let = cacheDir.listFiles()?.let {
        it.map { file ->
            SharedFileInfo(file.toUri().toString(), file.name)
        }
    }.orEmpty()
    shares.tryEmit(let + list)
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            addFile(uri)
        }

    private fun addFile(uri: Uri?) {
        uri ?: return
        lifecycleScope.launch {
            saveUri(uri)
        }

    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private suspend fun saveUri(uri: Uri) {
        val file = savedUriFile()
        withContext(Dispatchers.IO) {
            file.appendText(uri.toString() + "\n")
            cacheInvalid()
        }
        fei?.channel?.send(SseEvent("refresh"))
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private suspend fun saveFile(extension: String?, uri: Uri) {
        try {
            withContext(Dispatchers.IO) {
                val tempFile = File.createTempFile("file-", ".$extension")
                val byteArray = ByteArray(1024)
                tempFile.outputStream().use { outs ->
                    contentResolver.openInputStream(uri)?.use { inp ->
                        while (true) {
                            val count = inp.read(byteArray)
                            if (count != -1) {
                                outs.write(byteArray, 0, count)
                            } else break
                        }
                    }
                }
                tempFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveFile: ", e)
            null
        } ?: return

        cacheInvalid()
        fei?.channel?.send(SseEvent("refresh"))
    }

    @OptIn(
        ExperimentalMaterial3Api::class, ObsoleteCoroutinesApi::class
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deleteItem: (SharedFileInfo) -> Unit = { path ->
            val uri = Uri.parse(path.uri)
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                removeUri(path)
            }
            cacheInvalid()
            fei?.channel?.trySend(SseEvent(data = "refresh"))
        }
        val saveToLocal: (SharedFileInfo) -> Unit = {
            val uri = Uri.parse(it.uri)
            assert(uri.scheme != "file")
            lifecycleScope.launch {
                saveFile(File(it.name).extension, uri)
                removeUri(it)
            }
        }
        val portFlow = dataStore.data.map {
            it[stringPreferencesKey("port")] ?: FeiService.defaultPort.toString()
        }
        setContent {
            val port by portFlow.collectAsState(initial = FeiService.defaultPort.toString())
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val navController = rememberNavController()
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
                        FloatingActionButton(onClick = {
                            pickFile.launch(arrayOf("*/*"))
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = "add file")
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
                            }
                        }
                    }
                }

            }
        }
        val intent = Intent(this, FeiService::class.java)
        startService(intent)
        if (fei == null) bindService(intent, connection, 0)
    }

    @Composable
    @OptIn(ExperimentalComposeUiApi::class)
    private fun SettingPage(port: String) {
        PrefsScreen(dataStore = LocalContext.current.dataStore) {
            prefsItem {
                EditTextPref(
                    key = "port",
                    title = "port",
                    summary = "server listen on $port",
                    dialogTitle = "setting port",
                    dialogMessage = "please input a valid port",
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
                        modifier = Modifier
                            .padding(8.dp)
                            .background(
                                LightGray, RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp, 4.dp)
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
                        contentDescription = "Restart Service"
                    )
                }
                IconButton(onClick = {
                    stopService()
                }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Stop Service"
                    )
                }
            },

            )
        if (showDialog)
            AlertDialog(
                onDismissRequest = { showDialog = false },
                confirmButton = {
                    Text(text = "OK", modifier = Modifier.clickable {
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

        NavigationDrawerItem(
            label = {
                Text(text = "首页")
            },
            selected = false,
            onClick = {
                navController.navigate("main")
                scope.launch {
                    drawerState.close()
                }
            })

        NavigationDrawerItem(
            label = {
                Text(text = "关于")
            },
            selected = false,
            onClick = {
                //                                navController.navigate("main")
                scope.launch {
                    drawerState.close()
                }
            })
        NavigationDrawerItem(
            label = {
                Text(text = "设置")
            },
            selected = false,
            onClick = {
                navController.navigate("settings")
                scope.launch {
                    drawerState.close()
                }
            })
    }

    private fun removeUri(path: SharedFileInfo) {
        val file = savedUriFile()
        val readText = file.readText()
        readText.trim().split("\n").filter {
            it.isNotEmpty() && it != path.uri
        }.joinToString("\n").let {
            file.writeText(it)
        }
    }

    var fei: FeiService.Fei? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Toast.makeText(this@MainActivity, "服务已连接", Toast.LENGTH_SHORT).show()
            val feiLocal = service as FeiService.Fei
            Log.i(TAG, "onServiceConnected: $feiLocal")
            fei = feiLocal
            feiLocal.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fei = null
            Toast.makeText(this@MainActivity, "服务已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        cacheInvalid()
    }

    companion object {
        private const val TAG = "MainActivity"
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
            SharedFile(collectAsState[it], deleteItem, saveToLocal, viewInfo)
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
        ShowQrCode("shares/$i", port)
    }

}

@Composable
private fun ShowQrCode(sub: String, port: String) {
    val width = 200
    var selectedIp by remember {
        mutableStateOf(FeiService.listenerAddress)
    }
    val image by produceState<Bitmap?>(initialValue = null, sub, selectedIp, port) {
        value = "http://$selectedIp:$port/$sub".createQRImage(width, width)
    }
    var expanded by remember { mutableStateOf(false) }

    val all by produceState(initialValue = listOf(FeiService.listenerAddress)) {
        value = allIp()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val local = image
        if (local != null) {
            val widthDp = LocalConfiguration.current.smallestScreenWidthDp - 200
            Image(
                bitmap = local.asImageBitmap(),
                contentDescription = "test",
                modifier = Modifier
                    .width(
                        widthDp.dp
                    )
                    .height(widthDp.dp)
            )
        }
        Text(
            text = selectedIp, modifier = Modifier
                .padding(8.dp)
                .background(
                    LightGray, RectangleShape
                )
                .padding(8.dp)
                .clickable {
                    expanded = true
                }, fontSize = 16.sp
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            all.forEach {
                DropdownMenuItem(text = {
                    Text(text = it)
                }, onClick = {
                    selectedIp = it
                    expanded = false
                })
            }
        }
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