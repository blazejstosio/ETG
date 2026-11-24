package org.etg.migration.data.transfer

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

enum class InterfaceKind {
    USB_TETHER,
    WIFI_LAN,
    WIFI_DIRECT,
    LOOPBACK_ADB
}

data class DetectedNetInterface(
    val name: String,
    val ipAddress: String,
    val kind: InterfaceKind
)

object NetworkInterfaceInspector {
    fun inspectInterfaces(): List<DetectedNetInterface> {
        val list = mutableListOf<DetectedNetInterface>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp) continue
                val n = iface.name.lowercase()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr !is Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    val kind = when {
                        iface.isLoopback || addr.isLoopbackAddress || host == "127.0.0.1" -> InterfaceKind.LOOPBACK_ADB
                        n.startsWith("usb") || n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("eth") ->
                            InterfaceKind.USB_TETHER
                        n.startsWith("p2p") -> InterfaceKind.WIFI_DIRECT
                        n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") ->
                            InterfaceKind.WIFI_LAN
                        else -> InterfaceKind.WIFI_LAN
                    }
                    list += DetectedNetInterface(
                        name = iface.name,
                        ipAddress = host,
                        kind = kind
                    )
                }
            }
        }
        return list.sortedWith(compareBy<DetectedNetInterface> {
            when (it.kind) {
                InterfaceKind.USB_TETHER -> 0
                InterfaceKind.WIFI_DIRECT -> 1
                InterfaceKind.WIFI_LAN -> 2
                InterfaceKind.LOOPBACK_ADB -> 3
            }
        }.thenBy { it.name })
    }

    fun bestWirelessHost(interfaces: List<DetectedNetInterface>): String =
        interfaces.firstOrNull { it.kind == InterfaceKind.WIFI_DIRECT }?.ipAddress
            ?: interfaces.firstOrNull { it.kind == InterfaceKind.WIFI_LAN }?.ipAddress
            ?: "127.0.0.1"

    fun bestWiredHost(interfaces: List<DetectedNetInterface>): String =
        interfaces.firstOrNull { it.kind == InterfaceKind.USB_TETHER }?.ipAddress
            ?: "127.0.0.1"

    fun openUsbTetheringSettingsIntent(): Intent =
        Intent().apply {
            setClassName("com.android.settings", "com.android.settings.TetherSettings")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun openFallbackWirelessSettingsIntent(): Intent =
        Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
