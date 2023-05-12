package com.storyteller_f.fei.ui.components

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.storyteller_f.fei.HidState
import com.storyteller_f.fei.MainActivity
import com.storyteller_f.fei.R
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

class ComposeBluetoothDevice(val name: String, val address: String)

@Composable
fun BoundDevice(device: ComposeBluetoothDevice, connectDevice: (String) -> Boolean) {
    Column(
        modifier = Modifier
            .padding(8.dp)
            .clickable {
                connectDevice(device.address)
            }
            .background(
                MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(8.dp)

    ) {
        val modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
        Text(
            text = device.name,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = modifier
        )
        Text(
            text = device.address,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = modifier
        )
    }
}

class HidPreviewProvider : PreviewParameterProvider<HidState> {
    override val values: Sequence<HidState>
        get() = sequenceOf(
            HidState.BluetoothOff, HidState.NoPermission, HidState.NoBond(listOf()), HidState.Done(
                ComposeBluetoothDevice("name", "address")
            )
        )

}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun HidScreen(
    @PreviewParameter(HidPreviewProvider::class) bluetoothState: HidState,
    requestPermission: () -> Unit = {},
    connectDevice: (String) -> Boolean = { false },
    sendText: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var content by remember {
        mutableStateOf("")
    }
    val toBluetoothSettings = {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        context.startActivity(intent)
    }

    val rootModifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight()
    when (bluetoothState) {
        HidState.NotSupport -> {
            OneCenter {
                Text(text = "not support")
            }
        }
        HidState.BluetoothOff -> {
            OneCenter {
                Text(text = stringResource(R.string.bluetooth_off_tip))
            }
        }

        HidState.NoPermission -> {
            OneCenter {
                Button(onClick = {
                    requestPermission()
                }) {
                    Text(text = stringResource(R.string.bluetooth_permission_tip))
                }
            }

        }

        is HidState.NoBond -> {
            val bondDevices = bluetoothState.bondDevices
            if (bondDevices.isEmpty()) {
                Column(
                    modifier = rootModifier,
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.no_bond_device_tip),
                        modifier = Modifier.padding(8.dp)
                    )
                    Button(onClick = toBluetoothSettings) {
                        Text(text = stringResource(R.string.bluetooth_pair_tip))
                    }
                }
            } else {
                Column(modifier = rootModifier.padding(8.dp)) {
                    Text(
                        text = stringResource(R.string.bond_devices_tip),
                        fontSize = 20.sp
                    )
                    Button(onClick = toBluetoothSettings) {
                        Text(text = stringResource(R.string.no_expacted_bluetooth_pair_tip))
                    }
                    LazyColumn {
                        items(bondDevices.size) {
                            BoundDevice(bondDevices[it], connectDevice)
                        }
                    }
                }
            }
            if (bluetoothState.connecting != null)
            AlertDialog(onDismissRequest = {  }, confirmButton = {  }, text = {
                Text(text = "connecting to ${bluetoothState.connecting}")
            })
        }

        is HidState.Done -> {
            Column(modifier = rootModifier.padding(8.dp)) {
                Text(
                    text = stringResource(
                        id = R.string.connected_device_tip,
                        bluetoothState.device.name
                    ), style = MaterialTheme.typography.titleMedium
                )
                Text(text = stringResource(R.string.test_case))
                Row {
                    Button(onClick = {
                        sendText("fei")
                    }) {
                        Text(text = "fei")
                    }
                    if (bluetoothState.device.name.contains("Mac")) {
                        Button(onClick = {
                            sendText("z")
                        }) {
                            Text(text = "z")
                        }
                        Button(onClick = {
                            sendText("/")
                        }) {
                            Text(text = "/")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(value = content, onValueChange = {
                        content = it
                    }, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        sendText(content)
                    }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(text = stringResource(id = R.string.send))
                    }
                }
                if (!keyboardInterceptor.contains(KeyboardInterfaceInterceptor.key)) {
                    Button(onClick = {
                        keyboardInterceptor.putIfAbsent(KeyboardInterfaceInterceptor.key, Dvorak)
                    }) {
                        Text(text = stringResource(R.string.plug_dvorak_keyboard_style))
                    }
                } else {
                    Button(onClick = {
                        keyboardInterceptor.remove(KeyboardInterfaceInterceptor.key)
                    }) {
                        Text(text = stringResource(R.string.unplug_dvorak_keyboard_style))
                    }
                }
            }
        }

    }
}

fun Context.isBonded(device: BluetoothDevice): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        )
            device.bondState == BluetoothDevice.BOND_BONDED
        else {
            return false
        }
    } else if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        device.bondState == BluetoothDevice.BOND_BONDED
    } else {
        throw Exception("impossible")
    }
}

fun Context.alreadyBondedDevices(bluetoothManager: BluetoothManager) =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            )
                setOf<BluetoothDevice>(*bluetoothManager.adapter.bondedDevices.toTypedArray())
            else {
                setOf()
            }
        }

        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED -> {
            setOf<BluetoothDevice>(*bluetoothManager.adapter.bondedDevices.toTypedArray())
        }

        else -> setOf()
    }


fun Context.permissionOk(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED
    }
}

fun Context.registerAsHid(
    bluetoothHidDevice: BluetoothHidDevice,
    registerCallback: BluetoothHidDevice.Callback
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "fei",
            "auth input address",
            "provider",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            MainActivity.Descriptor
        )
        bluetoothHidDevice.registerApp(
            sdp,
            null,
            null,
            Executors.newCachedThreadPool(),
            registerCallback
        )
    }
}

fun Context.unRegisterAsHid(hidDevice: BluetoothHidDevice?) {
    hidDevice ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        )
            hidDevice.unregisterApp()
        else {
            return
        }
    }
}

suspend fun Context.sendReport(
    hidDevice: BluetoothHidDevice,
    selectedDevice: BluetoothDevice,
    modification: Int,
    code: Int
) {
    Log.d(
        "System",
        "sendReport() called with: hidDevice = $hidDevice, selectedDevice = $selectedDevice, code = $modification, f = $code"
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            println(hidDevice.sendReport(selectedDevice, 2, byteArrayOf(modification.toByte(), code.toByte())))
            delay(100)
            println(hidDevice.sendReport(selectedDevice, 2, byteArrayOf(0, 0)))
            delay(100)
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            hidDevice.sendReport(selectedDevice, 2, byteArrayOf(modification.toByte(), code.toByte()))
            delay(100)
            hidDevice.sendReport(selectedDevice, 2, byteArrayOf(0, 0))
            delay(100)
        }
    }

}

fun Context.connectDevice(
    hidDevice: BluetoothHidDevice?,
    bondDevices: Set<BluetoothDevice>,
    address: String
): Boolean {
    hidDevice ?: return false
    val device = bondDevices.firstOrNull {
        it.address == address
    } ?: return false
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) hidDevice.connect(device) else false
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED
            ) hidDevice.connect(device) else false
        }
        else -> false
    }
}

inline fun String.toKeyCode(block: (Pair<Int, Int>) -> Unit) {
    keyboardInterceptor.values.fold(this) { s, f ->
        f.intercept(s)
    }.forEach {
        when (it) {
            in 'a'..'z' -> block(it - 'a' + 4 to 0)

            in 'A'..'Z' -> block(it - 'A' to 2)

            in '1'..'9' -> block(it - '1' + 30 to 0)

            '0' -> block(39 to 0)
            '-' -> block(45 to 0)
            '=' -> block(46 to 0)
            '[' -> block(47 to 0)
            ']' -> block(48 to 0)
            '\\' -> block(49 to 0)
            ':' -> block(51 to 2)
            '.' -> block(55 to 0)
            '/' -> block(56 to 0)
            else -> throw Exception("$it not recognized")
        }

    }
}

val keyboardInterceptor = mutableStateMapOf<String, KeyboardInterceptor>()

const val qwerty = "-=qwertyuiop[]asdfghjkl;'zxcvbnm,./"
const val dvorak = "']x,doktfgsr-=a;hyujcvpzq/bi.nlmwe["

interface KeyboardInterceptor {
    fun intercept(data: String): String
}

interface KeyboardInterfaceInterceptor : KeyboardInterceptor {
    companion object {
        const val key = "keyboard style"
    }
}

object Dvorak : KeyboardInterfaceInterceptor {
    override fun intercept(data: String): String {
        assert(qwerty.length == dvorak.length)
        return data.map {
            val indexOf = qwerty.indexOf(it)
            if (indexOf >= 0) {
                dvorak[indexOf]
            } else it
        }.joinToString("")
    }
}

fun closeBluetoothProfile(
    bluetoothManager: BluetoothManager, bluetoothHidDevice: BluetoothHidDevice?
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        bluetoothManager.adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, bluetoothHidDevice)
    }
}
