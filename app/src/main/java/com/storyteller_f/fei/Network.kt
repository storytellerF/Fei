package com.storyteller_f.fei

import java.net.NetworkInterface

fun allIp(): List<String> {
    val set = mutableSetOf<String>()
    for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
        for (interfaceAddress in networkInterface.interfaceAddresses) {
            interfaceAddress.address.hostAddress?.let { set.add(it) }
        }
        for (inetAddress in networkInterface.inetAddresses) {
            inetAddress.hostAddress?.let { set.add(it) }
        }
    }
    return set.toList()
}