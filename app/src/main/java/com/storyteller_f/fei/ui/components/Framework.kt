package com.storyteller_f.fei.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.jamal.composeprefs3.ui.PrefsScreen
import com.jamal.composeprefs3.ui.prefs.EditTextPref
import com.storyteller_f.fei.service.FeiService
import com.storyteller_f.fei.R
import com.storyteller_f.fei.dataStore

@Preview
@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
fun FeiMainToolbar(
    port: String = "8080",
    restartService: () -> Unit = {},
    stopService: () -> Unit = {},
    sendText: (String) -> Unit = {},
    openDrawer: () -> Unit = {},
) {
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
                openDrawer()
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
                ShowQrCode(sub = "", port = port) {
                    showDialog = false
                    sendText(it)
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            ),
        )
}

@Preview
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun SettingPage(port: String = "8080") {
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

@Preview
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NavDrawer(closeDrawer: () -> Unit = {}, navigateTo: (String) -> Unit = {}, openAboutPage: () -> Unit = {}) {

    NavigationDrawerItem(
        label = {
            Text(text = stringResource(R.string.home))
        },
        icon = {
            Icon(Icons.Filled.Home, contentDescription = stringResource(id = R.string.home))
        },
        selected = false,
        onClick = {
            navigateTo("main")
            closeDrawer()
        })

    NavigationDrawerItem(label = { Text(text = stringResource(R.string.messages)) }, icon = {
        Icon(Icons.Filled.AccountBox, contentDescription = stringResource(R.string.messages))
    }, selected = false, onClick = {
        navigateTo("messages")
        closeDrawer()
    })

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        NavigationDrawerItem(label = { Text(text = "hid") }, icon = {
            Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.messages))
        }, selected = false, onClick = {
            navigateTo("hid")
            closeDrawer()
        })
    }

    NavigationDrawerItem(
        label = {
            Text(text = stringResource(R.string.about))
        },
        icon = {
            Icon(Icons.Filled.Info, contentDescription = stringResource(id = R.string.about))
        },
        selected = false,
        onClick = {
            openAboutPage()
            closeDrawer()
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
            navigateTo("settings")
            closeDrawer()
        })
}