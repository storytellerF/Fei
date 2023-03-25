package com.storyteller_f.fei.ui.components

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.storyteller_f.fei.HidState
import com.storyteller_f.fei.MainActivity
import java.util.concurrent.Executors

class ComposeBluetoothDevice(val name: String, val address: String)

@Composable
fun BoundDevice(device: ComposeBluetoothDevice, connectDevice: (String) -> Boolean) {
    Column(
        modifier = Modifier
            .padding(8.dp)
            .background(
                MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(8.dp)
            .clickable {
                connectDevice(device.address)
            }
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

@Composable
fun HidScreen(
    bluetoothState: HidState,
    requestPermission: () -> Unit = {},
    bondDevices: List<ComposeBluetoothDevice> = listOf(),
    connectDevice: (String) -> Boolean,
    sendText: (String) -> Unit = {},
) {
    when (bluetoothState) {
        HidState.BluetoothOff -> {
            Text(text = "请打开蓝牙")
        }

        HidState.NoPermission -> {
            Button(onClick = {
                requestPermission()
            }) {
                Text(text = "没有权限，点击授权访问Bluetooth Connect")
            }
        }

        HidState.NoBond -> {
            if (bondDevices.isEmpty()) {
                Text(text = "没有设备可供连接")
            } else {
                Column {
                    Text(text = "选择你的设备", modifier = Modifier.padding(8.dp), fontSize = 20.sp)
                    LazyColumn {
                        items(bondDevices.size) {
                            BoundDevice(bondDevices[it], connectDevice)
                        }
                    }
                }
            }
        }

        HidState.Done -> {
            Column {
                Button(onClick = {
                    sendText("fei")
                }) {
                    Text(text = "测试键盘")
                }
                Button(onClick = {
                    sendText("Z")
                }) {
                    Text(text = "Z")
                }
                Button(onClick = {
                    sendText("/")
                }) {
                    Text(text = "/")
                }
            }
        }
    }
}

fun Context.isBonded(device: BluetoothDevice): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return device.bondState == BluetoothDevice.BOND_BONDED
    } else {
        //todo 低版本没有bluetooth connect，需要搭配location 使用
        throw Exception("not implementation")
    }
}

fun Context.permissionOk(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        //todo 低版本没有bluetooth connect，需要搭配location 使用
        throw Exception("not implementation")
    }
}

fun Context.registerApp(
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
            "auth input adress",
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

fun Context.unRegister(hidDevice: BluetoothHidDevice?) {
    hidDevice ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        hidDevice.unregisterApp()
    }
}

fun Context.sendReport(
    hidDevice: BluetoothHidDevice,
    selectedDevice: BluetoothDevice,
    m: Int,
    it: Int
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        hidDevice.sendReport(selectedDevice, 2, byteArrayOf(m.toByte(), it.toByte()))
        Thread.sleep(100)
        hidDevice.sendReport(selectedDevice, 2, byteArrayOf(0, 0))
    }
}

fun String.toKeyCode(block: (Pair<Int, Int>) -> Unit) {
    forEach {
        when (it) {
            in 'a'..'z' -> {
                block(it - 'a' + 4 to 0)
            }

            in '1'..'9' -> {
                block(it - '1' + 30 to 0)
            }

            '0' -> block(39 to 0)
            '-' -> block(45 to 0)
            ':' -> block(51 to 2)
            '.' -> block(55 to 0)
            '/' -> block(56 to 0)
            else -> throw Exception("$it not recognized")
        }

    }
}