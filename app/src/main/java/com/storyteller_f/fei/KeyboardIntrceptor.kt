package com.storyteller_f.fei

import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf


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

fun BluetoothManager.closeBluetoothProfile(
    bluetoothHidDevice: BluetoothHidDevice?
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, bluetoothHidDevice)
    }
}
