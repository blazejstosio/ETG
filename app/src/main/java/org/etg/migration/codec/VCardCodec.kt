package org.etg.migration.codec

import org.etg.migration.model.ContactAddress
import org.etg.migration.model.ContactEmail
import org.etg.migration.model.ContactEvent
import org.etg.migration.model.ContactPhone
import org.etg.migration.model.ContactRecord
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

object VCardCodec {
    fun encodeAll(contacts: List<ContactRecord>): String {
        val sb = StringBuilder()
        for (contact in contacts) {
            sb.append(encodeSingle(contact))
        }
        return sb.toString()
    }

    fun encodeSingle(contact: ContactRecord): String {
        val lines = mutableListOf<String>()
        lines += "BEGIN:VCARD"
        lines += "VERSION:3.0"
        val fn = contact.displayName.ifBlank {
            listOf(contact.givenName, contact.middleName, contact.familyName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { contact.phones.firstOrNull()?.number ?: "Unknown" }
        }
        lines += "FN:${escapeValue(fn)}"
        lines += "N:${escapeValue(contact.familyName)};${escapeValue(contact.givenName)};${escapeValue(contact.middleName)};${escapeValue(contact.prefix)};${escapeValue(contact.suffix)}"

        for (phone in contact.phones) {
            val typeStr = phoneTypeToVCard(phone.type)
            lines += "TEL;TYPE=$typeStr:${escapeValue(phone.number)}"
        }
        for (email in contact.emails) {
            val typeStr = emailTypeToVCard(email.type)
            lines += "EMAIL;TYPE=$typeStr:${escapeValue(email.address)}"
        }
        for (adr in contact.addresses) {
            val typeStr = adrTypeToVCard(adr.type)
            val street = adr.street.ifBlank { adr.formatted }
            lines += "ADR;TYPE=$typeStr:;;${escapeValue(street)};${escapeValue(adr.city)};${escapeValue(adr.region)};${escapeValue(adr.postalCode)};${escapeValue(adr.country)}"
        }
        if (contact.organization.isNotBlank()) {
            lines += "ORG:${escapeValue(contact.organization)}"
        }
        if (contact.jobTitle.isNotBlank()) {
            lines += "TITLE:${escapeValue(contact.jobTitle)}"
        }
        for (event in contact.events) {
            if (event.type == 3 && event.startDate.isNotBlank()) {
                lines += "BDAY:${escapeValue(event.startDate)}"
            }
        }
        if (contact.note.isNotBlank()) {
            lines += "NOTE:${escapeValue(contact.note)}"
        }
        for (url in contact.websites) {
            if (url.isNotBlank()) {
                lines += "URL:${escapeValue(url)}"
            }
        }
        if (contact.groups.isNotEmpty()) {
            lines += "CATEGORIES:${contact.groups.joinToString(",") { escapeValue(it) }}"
        }
        if (!contact.photoBase64.isNullOrBlank()) {
            val cleanB64 = contact.photoBase64.replace("\r", "").replace("\n", "")
            lines += "PHOTO;ENCODING=b;TYPE=JPEG:$cleanB64"
        }
        lines += "END:VCARD"

        val out = StringBuilder()
        for (line in lines) {
            out.append(foldLine(line)).append("\r\n")
        }
        return out.toString()
    }

    fun decodeAll(vcfContent: String): List<ContactRecord> {
        val unfolded = unfoldLines(vcfContent)
        val result = mutableListOf<ContactRecord>()
        var inCard = false
        var displayName = ""
        var givenName = ""
        var familyName = ""
        var middleName = ""
        var prefix = ""
        var suffix = ""
        var org = ""
        var title = ""
        var note = ""
        val phones = mutableListOf<ContactPhone>()
        val emails = mutableListOf<ContactEmail>()
        val addresses = mutableListOf<ContactAddress>()
        val events = mutableListOf<ContactEvent>()
        val websites = mutableListOf<String>()
        val groups = mutableListOf<String>()
        var photoBase64: String? = null

        for (rawLine in unfolded) {
            val line = rawLine.trimEnd('\r', '\n')
            if (line.equals("BEGIN:VCARD", ignoreCase = true)) {
                inCard = true
                displayName = ""
                givenName = ""
                familyName = ""
                middleName = ""
                prefix = ""
                suffix = ""
                org = ""
                title = ""
                note = ""
                phones.clear()
                emails.clear()
                addresses.clear()
                events.clear()
                websites.clear()
                groups.clear()
                photoBase64 = null
                continue
            }
            if (line.equals("END:VCARD", ignoreCase = true)) {
                if (inCard) {
                    val computedDisplay = displayName.ifBlank {
                        listOf(prefix, givenName, middleName, familyName, suffix)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                    }
                    result += ContactRecord(
                        displayName = computedDisplay,
                        givenName = givenName,
                        familyName = familyName,
                        middleName = middleName,
                        prefix = prefix,
                        suffix = suffix,
                        organization = org,
                        jobTitle = title,
                        note = note,
                        phones = phones.toList(),
                        emails = emails.toList(),
                        addresses = addresses.toList(),
                        events = events.toList(),
                        websites = websites.toList(),
                        groups = groups.toList(),
                        photoBase64 = photoBase64
                    )
                }
                inCard = false
                continue
            }
            if (!inCard || line.isBlank()) continue

            val colonIdx = findUnquotedColon(line)
            if (colonIdx <= 0) continue
            val headerPart = line.substring(0, colonIdx)
            val rawValue = line.substring(colonIdx + 1)

            val parts = headerPart.split(';')
            val propName = parts[0].substringAfter('.').uppercase()
            val params = parts.drop(1).map { it.uppercase() }
            val isQp = params.any { it.contains("QUOTED-PRINTABLE") }
            val decodedValue = if (isQp) decodeQuotedPrintable(rawValue) else rawValue

            when (propName) {
                "FN" -> displayName = unescapeValue(decodedValue)
                "N" -> {
                    val comps = splitEscaped(decodedValue, ';')
                    familyName = unescapeValue(comps.getOrElse(0) { "" })
                    givenName = unescapeValue(comps.getOrElse(1) { "" })
                    middleName = unescapeValue(comps.getOrElse(2) { "" })
                    prefix = unescapeValue(comps.getOrElse(3) { "" })
                    suffix = unescapeValue(comps.getOrElse(4) { "" })
                }
                "TEL" -> {
                    val number = unescapeValue(decodedValue).trim()
                    if (number.isNotBlank()) {
                        phones += ContactPhone(number = number, type = vCardToPhoneType(params))
                    }
                }
                "EMAIL" -> {
                    val addr = unescapeValue(decodedValue).trim()
                    if (addr.isNotBlank()) {
                        emails += ContactEmail(address = addr, type = vCardToEmailType(params))
                    }
                }
                "ADR" -> {
                    val comps = splitEscaped(decodedValue, ';')
                    val street = unescapeValue(comps.getOrElse(2) { "" })
                    val city = unescapeValue(comps.getOrElse(3) { "" })
                    val region = unescapeValue(comps.getOrElse(4) { "" })
                    val postal = unescapeValue(comps.getOrElse(5) { "" })
                    val country = unescapeValue(comps.getOrElse(6) { "" })
                    val formatted = listOf(street, city, region, postal, country)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    if (formatted.isNotBlank()) {
                        addresses += ContactAddress(
                            formatted = formatted,
                            street = street,
                            city = city,
                            region = region,
                            postalCode = postal,
                            country = country,
                            type = vCardToAdrType(params)
                        )
                    }
                }
                "ORG" -> org = unescapeValue(decodedValue).replace(';', ' ').trim()
                "TITLE" -> title = unescapeValue(decodedValue)
                "BDAY" -> {
                    val bday = unescapeValue(decodedValue).trim()
                    if (bday.isNotBlank()) {
                        events += ContactEvent(startDate = bday, type = 3)
                    }
                }
                "NOTE" -> note = unescapeValue(decodedValue)
                "URL" -> {
                    val url = unescapeValue(decodedValue).trim()
                    if (url.isNotBlank()) websites += url
                }
                "CATEGORIES" -> {
                    splitEscaped(decodedValue, ',').forEach { grp ->
                        val clean = unescapeValue(grp).trim()
                        if (clean.isNotBlank()) groups += clean
                    }
                }
                "PHOTO" -> {
                    val clean = rawValue.replace("\\s".toRegex(), "")
                    if (clean.isNotBlank()) {
                        runCatching {
                            Base64.getDecoder().decode(clean)
                            photoBase64 = clean
                        }
                    }
                }
            }
        }
        return result
    }

    internal fun unfoldLines(text: String): List<String> {
        val rawLines = text.replace("\r\n", "\n").replace("\r", "\n").split('\n')
        val unfolded = mutableListOf<String>()
        var qpContinuation = false
        for (line in rawLines) {
            if (qpContinuation && unfolded.isNotEmpty()) {
                val prev = unfolded.removeAt(unfolded.lastIndex)
                val trimmedPrev = if (prev.endsWith("=")) prev.dropLast(1) else prev
                unfolded += (trimmedPrev + line)
                qpContinuation = line.endsWith("=")
                continue
            }
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                val prev = unfolded.removeAt(unfolded.lastIndex)
                unfolded += (prev + line.substring(1))
            } else {
                unfolded += line
            }
            qpContinuation = line.contains("QUOTED-PRINTABLE", ignoreCase = true) && line.endsWith("=")
        }
        return unfolded
    }

    internal fun foldLine(line: String): String {
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= 75) return line
        val out = StringBuilder()
        var currentLineBytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val charStr = String(Character.toChars(cp))
            val charBytes = charStr.toByteArray(StandardCharsets.UTF_8).size
            val limit = if (out.isEmpty()) 75 else 74
            if (currentLineBytes + charBytes > limit && currentLineBytes > 0) {
                out.append("\r\n ")
                currentLineBytes = 0
            }
            out.append(charStr)
            currentLineBytes += charBytes
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun escapeValue(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace(";", "\\;")
            .replace(",", "\\,")

    private fun unescapeValue(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                when (next) {
                    'n', 'N' -> {
                        sb.append('\n')
                        i += 2
                        continue
                    }
                    '\\', ';', ',' -> {
                        sb.append(next)
                        i += 2
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun splitEscaped(input: String, delimiter: Char): List<String> {
        val list = mutableListOf<String>()
        val cur = StringBuilder()
        var escaped = false
        for (c in input) {
            if (escaped) {
                cur.append(c)
                escaped = false
            } else if (c == '\\') {
                cur.append(c)
                escaped = true
            } else if (c == delimiter) {
                list += cur.toString()
                cur.clear()
            } else {
                cur.append(c)
            }
        }
        list += cur.toString()
        return list
    }

    private fun findUnquotedColon(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ':' && !inQuotes) return i
        }
        return -1
    }

    private fun decodeQuotedPrintable(input: String): String {
        val baos = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '=' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3)
                val parsed = hex.toIntOrNull(16)
                if (parsed != null) {
                    baos.write(parsed)
                    i += 3
                    continue
                }
            }
            val utf8 = c.toString().toByteArray(StandardCharsets.UTF_8)
            baos.write(utf8)
            i++
        }
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    private fun phoneTypeToVCard(type: Int): String = when (type) {
        1 -> "HOME,VOICE"
        2 -> "CELL,VOICE"
        3 -> "WORK,VOICE"
        4 -> "WORK,FAX"
        5 -> "HOME,FAX"
        12 -> "MAIN"
        else -> "OTHER"
    }

    private fun vCardToPhoneType(params: List<String>): Int = when {
        params.any { it.contains("CELL") } -> 2
        params.any { it.contains("WORK") } -> 3
        params.any { it.contains("HOME") } -> 1
        params.any { it.contains("MAIN") } -> 12
        else -> 7
    }

    private fun emailTypeToVCard(type: Int): String = when (type) {
        1 -> "HOME,INTERNET"
        2 -> "WORK,INTERNET"
        else -> "INTERNET"
    }

    private fun vCardToEmailType(params: List<String>): Int = when {
        params.any { it.contains("WORK") } -> 2
        params.any { it.contains("HOME") } -> 1
        else -> 3
    }

    private fun adrTypeToVCard(type: Int): String = when (type) {
        1 -> "HOME"
        2 -> "WORK"
        else -> "OTHER"
    }

    private fun vCardToAdrType(params: List<String>): Int = when {
        params.any { it.contains("WORK") } -> 2
        params.any { it.contains("HOME") } -> 1
        else -> 3
    }
}
