package org.etg.migration.data.exporter

import org.etg.migration.R
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import kotlinx.serialization.encodeToString
import org.etg.migration.codec.WifiQrAndXmlParser
import org.etg.migration.data.archive.ArchiveSink
import org.etg.migration.data.archive.EtgJson
import org.etg.migration.model.CategoryScanInfo
import org.etg.migration.model.CategoryStatus
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.WifiNetworkEntry
import org.etg.migration.model.WifiSecurityType
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object WifiExporter {
    fun scan(context: Context, queuedNetworks: List<WifiNetworkEntry>): CategoryScanInfo {
        val currentSsid = getCurrentConnectedSsid(context)
        val count = queuedNetworks.size
        val summary = buildString {
            append(context.getString(R.string.scan_wifi_saved, count))
            if (!currentSsid.isNullOrBlank()) {
                append(" • ").append(context.getString(R.string.scan_wifi_active, currentSsid))
            }
        }
        return CategoryScanInfo(
            category = ExportCategory.WIFI_NETWORKS,
            itemCount = count,
            estimatedBytes = count * 512L + 512L,
            status = if (count > 0) CategoryStatus.READY else CategoryStatus.GUIDED,
            detailSummary = summary
        )
    }

    fun getCurrentConnectedSsid(context: Context): String? {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val active = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(active)
                val info = caps?.transportInfo as? WifiInfo
                val ssid = cleanSsid(info?.ssid)
                if (!ssid.isNullOrBlank()) return ssid
            }
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ssid = cleanSsid(wm?.connectionInfo?.ssid)
            if (!ssid.isNullOrBlank()) return ssid
        }
        return null
    }

    private fun cleanSsid(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        if (s.isBlank() || s == "<unknown ssid>") return null
        return s
    }

    fun tryReadRootWifiXml(): List<WifiNetworkEntry> {
        return runCatching {
            val process = ProcessBuilder(
                "su",
                "-c",
                "cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null || cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null"
            ).redirectErrorStream(false).start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return emptyList()
            }
            val xml = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            if (xml.contains("<WifiConfigStoreData") || xml.contains("<NetworkList")) {
                WifiQrAndXmlParser.parseWifiConfigStoreXml(xml)
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    fun export(
        networks: List<WifiNetworkEntry>,
        sink: ArchiveSink
    ): Int {
        val unique = networks.distinctBy { it.ssid to it.securityType }
        val json = EtgJson.encodeToString(unique).toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("wifi/wifi_networks.json", ExportCategory.WIFI_NETWORKS, json, compress = true)

        val qrLines = buildString {
            appendLine("# ETG Exported Wi-Fi Networks (Standard Android Wi-Fi QR Strings)")
            for (n in unique) {
                appendLine("# SSID: ${n.ssid} | Type: ${n.securityType} | Hidden: ${n.hidden}")
                appendLine(WifiQrAndXmlParser.toWifiQrString(n))
                appendLine()
            }
        }.toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("wifi/wifi_qr_strings.txt", ExportCategory.WIFI_NETWORKS, qrLines, compress = true)
        return unique.size
    }

    fun decodeNetworks(jsonBytes: ByteArray?): List<WifiNetworkEntry> {
        if (jsonBytes == null) return emptyList()
        return runCatching {
            EtgJson.decodeFromString<List<WifiNetworkEntry>>(String(jsonBytes, StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun buildSaveNetworksIntent(networks: List<WifiNetworkEntry>): Intent? {
        val suggestions = ArrayList<WifiNetworkSuggestion>()
        for (entry in networks) {
            if (entry.ssid.isBlank()) continue
            val builder = WifiNetworkSuggestion.Builder()
                .setSsid(entry.ssid)
                .setIsHiddenSsid(entry.hidden)

            when (entry.securityType) {
                WifiSecurityType.WPA2 -> {
                    if (entry.password.length in 8..63) {
                        builder.setWpa2Passphrase(entry.password)
                    } else continue
                }
                WifiSecurityType.WPA3 -> {
                    if (entry.password.length in 8..63) {
                        builder.setWpa3Passphrase(entry.password)
                    } else continue
                }
                WifiSecurityType.OPEN -> Unit
                WifiSecurityType.WEP -> continue
            }
            suggestions += builder.build()
        }
        if (suggestions.isEmpty()) return null
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, suggestions)
        }
    }
}
