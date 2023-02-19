package com.storyteller_f.fei

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.storyteller_f.fei.ui.theme.FeiTheme
val shares = mutableListOf<Uri>()
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Localized description")
                            }
                            IconButton(onClick = {
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Localized description")
                            }
                        },

                        )
                }, floatingActionButton = {
                    FloatingActionButton(onClick = {

                    }) {
                        /* FAB content */
                    }
                }) { paddingValues ->
                    // A surface container using the 'background' color from the theme
                    Surface(modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues), color = MaterialTheme.colorScheme.background) {
                        Main("Android")
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

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun Main(name: String) {
    Text(text = "Hello $name!")
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    FeiTheme {
        Main("Android")
    }
}