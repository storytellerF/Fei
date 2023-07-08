package com.storyteller_f.fei

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

sealed interface BluetoothAction {
    class NotSupport : BluetoothAction

    class Done(val result: Boolean, val message: String) : BluetoothAction

    object PermissionDenied : BluetoothAction
}

private fun Boolean.done(): BluetoothAction.Done {
    return BluetoothAction.Done(this, "")
}

fun Context.alreadyBondedDevices(bluetoothManager: BluetoothManager): Set<BluetoothDevice> {
    val adapter = bluetoothManager.adapter
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            )
                adapter?.bondedDevices.orEmpty().toSet()
            else setOf()
        }

        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED -> {
            adapter?.bondedDevices.orEmpty().toSet()
        }

        else -> setOf()
    }
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
): BluetoothAction {
    hidDevice ?: return BluetoothAction.Done(false, "未连接HID")
    val device = bondDevices.firstOrNull {
        it.address == address
    } ?: return BluetoothAction.Done(false, "设备$address 不存在")
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) hidDevice.connect(device).done() else BluetoothAction.PermissionDenied
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED
            ) hidDevice.connect(device).done() else BluetoothAction.PermissionDenied
        }
        else -> BluetoothAction.NotSupport()
    }
}

fun Context.disconnectDevice(
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
            ) hidDevice.disconnect(device) else false
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED
            ) hidDevice.disconnect(device) else false
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
