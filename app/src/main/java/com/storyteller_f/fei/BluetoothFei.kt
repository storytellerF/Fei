package com.storyteller_f.fei

import android.bluetooth.BluetoothManager
import android.content.Context

class BluetoothFei(val context: Context) {
    val bluetoothManager = context.getSystemService(BluetoothManager::class.java)

    init {

    }
}