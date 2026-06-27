package com.jollywhoppers.atproto.server

import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest

object ServerIdentity {
    fun buildServerId(): String {
        val separator = System.lineSeparator()
        val sb = StringBuilder()
        try {
            sb.append("os_name:").append(System.getProperty("os.name")).append(separator)
            sb.append("os_version:").append(System.getProperty("os.version")).append(separator)
            sb.append("os_arch:").append(System.getProperty("os.arch")).append(separator)
            val host = InetAddress.getLocalHost()
            sb.append("host_name:").append(host.hostName).append(separator)
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val ni = networkInterfaces.nextElement()
                val hw = ni.hardwareAddress
                if (hw != null && hw.isNotEmpty()) {
                    sb.append("mac:").append(hw.joinToString(":") { "%02x".format(it) }).append(separator)
                    break
                }
            }
        } catch (_: Exception) { }
        val hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
}
