package com.storyteller_f.fei

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.storyteller_f.fei.ui.theme.FeiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val shares = MutableStateFlow<List<SharedFileInfo>>(listOf())

fun Context.cacheInvalid() {
    val listFile = File(filesDir, "list.txt")
    if (!listFile.exists()) listFile.createNewFile()
    val readText = listFile.readText()

    val list = readText.split("\n").filter {
        it.isNotEmpty()
    }.mapNotNull {
        val toUri = it.toUri()
        try {
            val name = contentResolver.query(toUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val string = cursor.getString(columnIndex)
                    string
                } else "unknown"
            } ?: "unknown"
            SharedFileInfo(toUri, name)
        } catch (e: Exception) {
            null
        }
    }
    listFile.writeText(list.joinToString("\n") {
        it.uri.toString()
    })
    val let = cacheDir.listFiles()?.let {
        it.map { file ->
            SharedFileInfo(file.toUri(), file.name)
        }
    }.orEmpty()
    shares.tryEmit(let + list)
}

class MainActivity : ComponentActivity() {
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        addFile(uri)
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private fun addFile(uri: Uri?) {
        uri ?: return
        Toast.makeText(this, "waiting", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                File(filesDir, "list.txt").appendText(uri.toString() + "\n")
                cacheInvalid()
            }
            fei?.channel?.send(SseEvent("refresh"))
        }

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

    @OptIn(ExperimentalMaterial3Api::class, ObsoleteCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deleteItem: (String) -> Unit = { path ->
            File(path).delete()
            cacheInvalid()
            fei?.channel?.trySend(SseEvent(data = "refresh"))
        }
        setContent {
            FeiTheme {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("Simple TopAppBar") },
                        navigationIcon = {
                            IconButton(onClick = { /* doSomething() */ }) {
                                Icon(Icons.Filled.Menu, contentDescription = null)
                            }
                        },
                        actions = {
                            // RowScope here, so these icons will be placed horizontally
                            IconButton(onClick = {
                                fei?.restart()
                            }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Localized description")
                            }
                            IconButton(onClick = {
                                fei?.stop()
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Localized description")
                            }
                        },

                        )
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
                            .padding(paddingValues), color = MaterialTheme.colorScheme.background
                    ) {
                        Main(shares, deleteItem)
                    }
                }

            }
        }
        val intent = Intent(this, FeiService::class.java)
        startService(intent)
        bindService(intent, connection, 0)
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
fun Main(flow: MutableStateFlow<List<SharedFileInfo>>, deleteItem: (String) -> Unit = {}) {
    val collectAsState by flow.collectAsState()

    LazyColumn(content = {
        items(collectAsState.size) {
            SharedFile(collectAsState[it], deleteItem)
        }
    })
}

class ShareFilePreviewProvider : PreviewParameterProvider<SharedFileInfo> {
    override val values: Sequence<SharedFileInfo>
        get() = sequenceOf(SharedFileInfo(Uri.EMPTY, "world"))

}

@Preview
@Composable
private fun SharedFile(@PreviewParameter(ShareFilePreviewProvider::class) info: SharedFileInfo, deleteItem: (String) -> Unit = {}) {
    Column(modifier = Modifier
        .clickable {
            //todo delete
//            deleteItem(info)
        }
        .fillMaxWidth()
        .padding(10.dp)) {
        Text(text = info.uri.toString())
        Text(text = info.name)
    }

}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    FeiTheme {
        Main(MutableStateFlow(ShareFilePreviewProvider().values.toList()))
    }
}