package org.etg.migration.codec

import org.etg.migration.model.WifiNetworkEntry
import org.etg.migration.model.WifiSecurityType
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

object WifiQrAndXmlParser {
    fun parseWifiQr(raw: String): WifiNetworkEntry? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("WIFI:", ignoreCase = true)) return null
        val body = trimmed.substring(5)
        val tokens = splitWifiTokens(body)

        var ssid: String? = null
        var password = ""
        var typeRaw = ""
        var hidden = false

        for (token in tokens) {
            if (token.isEmpty()) continue
            val colonIdx = token.indexOf(':')
            if (colonIdx <= 0) continue
            val key = token.substring(0, colonIdx).uppercase()
            val value = unescapeWifiValue(token.substring(colonIdx + 1))
            when (key) {
                "S" -> ssid = value
                "P" -> password = value
                "T" -> typeRaw = value.uppercase()
                "H" -> hidden = value.equals("true", ignoreCase = true)
            }
        }

        val cleanSsid = ssid?.takeIf { it.isNotEmpty() } ?: return null
        val secType = when {
            typeRaw.contains("SAE") || typeRaw.contains("WPA3") -> WifiSecurityType.WPA3
            typeRaw.contains("WEP") -> WifiSecurityType.WEP
            typeRaw == "NOPASS" || (typeRaw.isBlank() && password.isEmpty()) -> WifiSecurityType.OPEN
            else -> WifiSecurityType.WPA2
        }

        return WifiNetworkEntry(
            ssid = cleanSsid,
            password = if (secType == WifiSecurityType.OPEN) "" else password,
            securityType = secType,
            hidden = hidden,
            source = "qr"
        )
    }

    fun toWifiQrString(entry: WifiNetworkEntry): String {
        val typeCode = when (entry.securityType) {
            WifiSecurityType.WPA3 -> "SAE"
            WifiSecurityType.WPA2 -> "WPA"
            WifiSecurityType.WEP -> "WEP"
            WifiSecurityType.OPEN -> "nopass"
        }
        val sb = StringBuilder("WIFI:")
        sb.append("T:").append(typeCode).append(';')
        sb.append("S:").append(escapeWifiValue(entry.ssid)).append(';')
        if (entry.securityType != WifiSecurityType.OPEN && entry.password.isNotEmpty()) {
            sb.append("P:").append(escapeWifiValue(entry.password)).append(';')
        }
        if (entry.hidden) {
            sb.append("H:true;")
        }
        sb.append(';')
        return sb.toString()
    }

    fun parseWifiConfigStoreXml(xmlContent: String): List<WifiNetworkEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlContent)))
        val networkNodes = doc.getElementsByTagName("Network")
        val result = linkedMapOf<Pair<String, WifiSecurityType>, WifiNetworkEntry>()

        for (i in 0 until networkNodes.length) {
            val networkEl = networkNodes.item(i) as? Element ?: continue
            val wifiConfigEl = findDirectOrNestedElement(networkEl, "WifiConfiguration") ?: continue

            val rawSsid = readNamedValue(wifiConfigEl, "SSID") ?: continue
            val ssid = decodeConfigStoreString(rawSsid).trim()
            if (ssid.isBlank()) continue

            val rawPsk = readNamedValue(wifiConfigEl, "PreSharedKey")
            val password = rawPsk?.let { decodeConfigStoreString(it) } ?: ""
            val wepKeys = readWepKeys(wifiConfigEl)
            val hidden = readNamedValue(wifiConfigEl, "HiddenSSID")
                ?.equals("true", ignoreCase = true) == true
            val configKey = readNamedValue(wifiConfigEl, "ConfigKey") ?: ""

            val securityTypes = collectSecurityMetadata(networkEl, wifiConfigEl, configKey)
            val secType = when {
                securityTypes.contains("SAE") || securityTypes.contains("SECURITY_TYPE_SAE") -> WifiSecurityType.WPA3
                wepKeys.isNotEmpty() || securityTypes.contains("WEP") -> WifiSecurityType.WEP
                password.isNotEmpty() || securityTypes.contains("WPA_PSK") || securityTypes.contains("PSK") -> WifiSecurityType.WPA2
                else -> WifiSecurityType.OPEN
            }

            val finalPassword = when (secType) {
                WifiSecurityType.WEP -> wepKeys.firstOrNull().orEmpty().ifEmpty { password }
                WifiSecurityType.OPEN -> ""
                else -> password
            }

            val entry = WifiNetworkEntry(
                ssid = ssid,
                password = finalPassword,
                securityType = secType,
                hidden = hidden,
                source = "xml"
            )
            result[ssid to secType] = entry
        }
        return result.values.toList()
    }

    private fun findDirectOrNestedElement(parent: Element, tagName: String): Element? {
        val list = parent.getElementsByTagName(tagName)
        return if (list.length > 0) list.item(0) as? Element else null
    }

    private fun readNamedValue(container: Element, targetName: String): String? {
        val children = container.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            val nameAttr = el.getAttribute("name")
            if (nameAttr == targetName) {
                val valueAttr = el.getAttribute("value")
                if (valueAttr.isNotEmpty()) return valueAttr
                return el.textContent
            }
        }
        return null
    }

    private fun readWepKeys(container: Element): List<String> {
        val keys = mutableListOf<String>()
        val arrays = container.getElementsByTagName("string-array")
        for (i in 0 until arrays.length) {
            val arr = arrays.item(i) as? Element ?: continue
            if (arr.getAttribute("name") == "WEPKeys") {
                val items = arr.getElementsByTagName("item")
                for (j in 0 until items.length) {
                    val item = items.item(j) as? Element ?: continue
                    val value = decodeConfigStoreString(item.getAttribute("value").ifEmpty { item.textContent })
                    if (value.isNotBlank()) keys += value
                }
            }
        }
        return keys
    }

    private fun collectSecurityMetadata(
        networkEl: Element,
        wifiConfigEl: Element,
        configKey: String
    ): String {
        val sb = StringBuilder(configKey.uppercase())
        val tags = listOf("byte-array", "int", "string")
        for (tag in tags) {
            val list = wifiConfigEl.getElementsByTagName(tag)
            for (i in 0 until list.length) {
                val el = list.item(i) as? Element ?: continue
                val n = el.getAttribute("name")
                if (n.contains("KeyMgmt", ignoreCase = true) ||
                    n.contains("Security", ignoreCase = true)
                ) {
                    sb.append(' ').append(el.getAttribute("value")).append(' ').append(el.textContent)
                }
            }
        }
        val secParams = networkEl.getElementsByTagName("SecurityParams")
        for (i in 0 until secParams.length) {
            val sp = secParams.item(i) as? Element ?: continue
            val ints = sp.getElementsByTagName("int")
            for (j in 0 until ints.length) {
                val el = ints.item(j) as? Element ?: continue
                if (el.getAttribute("name") == "SecurityType") {
                    when (el.getAttribute("value")) {
                        "4" -> sb.append(" SAE ")
                        "2" -> sb.append(" PSK ")
                        "1" -> sb.append(" WEP ")
                    }
                }
            }
        }
        val bitSets = networkEl.getElementsByTagName("BitSet")
        for (i in 0 until bitSets.length) {
            val bs = bitSets.item(i) as? Element ?: continue
            sb.append(' ').append(bs.textContent)
        }
        return sb.toString().uppercase()
    }

    private fun decodeConfigStoreString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "null") return ""
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        if (trimmed.isNotEmpty() && trimmed.length % 2 == 0 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return runCatching {
                val bytes = ByteArray(trimmed.length / 2) { idx ->
                    trimmed.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
                }
                String(bytes, StandardCharsets.UTF_8)
            }.getOrDefault(trimmed)
        }
        return trimmed
    }

    private fun splitWifiTokens(body: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var escaped = false
        for (c in body) {
            if (escaped) {
                cur.append(c)
                escaped = false
            } else if (c == '\\') {
                cur.append(c)
                escaped = true
            } else if (c == ';') {
                tokens += cur.toString()
                cur.clear()
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) tokens += cur.toString()
        return tokens
    }

    private fun escapeWifiValue(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') {
                sb.append('\\')
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun unescapeWifiValue(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                sb.append(value[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
