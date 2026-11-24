package org.etg.migration.codec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.etg.migration.model.OtpAccount
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

object OtpMigrationCodec {
    data class Batch(
        val accounts: List<OtpAccount>,
        val batchIndex: Int,
        val batchSize: Int,
        val batchId: Int
    )

    fun isMigrationUri(raw: String): Boolean =
        raw.trim().startsWith("otpauth-migration://", ignoreCase = true)

    fun parse(raw: String): Batch? {
        val text = raw.trim()
        if (!isMigrationUri(text)) return null
        val query = text.substringAfter('?', "")
        val dataParam = query.split('&')
            .firstOrNull { it.startsWith("data=") }
            ?.substringAfter("data=")
            ?: return null
        val b64 = runCatching {
            URLDecoder.decode(dataParam, StandardCharsets.UTF_8.name()).replace(' ', '+')
        }.getOrNull() ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
        return runCatching { decodePayload(bytes) }.getOrNull()
    }

    private fun decodePayload(bytes: ByteArray): Batch {
        val reader = ProtoReader(bytes)
        val accounts = mutableListOf<OtpAccount>()
        var batchSize = 1
        var batchIndex = 0
        var batchId = 0
        while (reader.hasMore()) {
            val (field, wire) = reader.readTag()
            when {
                field == 1 && wire == 2 -> decodeOtp(reader.readBytes())?.let { accounts += it }
                field == 3 && wire == 0 -> batchSize = reader.readVarint().toInt()
                field == 4 && wire == 0 -> batchIndex = reader.readVarint().toInt()
                field == 5 && wire == 0 -> batchId = reader.readVarint().toInt()
                else -> reader.skip(wire)
            }
        }
        return Batch(accounts, batchIndex, batchSize.coerceAtLeast(1), batchId)
    }

    private fun decodeOtp(bytes: ByteArray): OtpAccount? {
        val r = ProtoReader(bytes)
        var secret = ByteArray(0)
        var name = ""
        var issuer = ""
        var algorithm = 1
        var digits = 1
        var type = 2
        var counter = 0L
        while (r.hasMore()) {
            val (field, wire) = r.readTag()
            when {
                field == 1 && wire == 2 -> secret = r.readBytes()
                field == 2 && wire == 2 -> name = String(r.readBytes(), StandardCharsets.UTF_8)
                field == 3 && wire == 2 -> issuer = String(r.readBytes(), StandardCharsets.UTF_8)
                field == 4 && wire == 0 -> algorithm = r.readVarint().toInt()
                field == 5 && wire == 0 -> digits = r.readVarint().toInt()
                field == 6 && wire == 0 -> type = r.readVarint().toInt()
                field == 7 && wire == 0 -> counter = r.readVarint()
                else -> r.skip(wire)
            }
        }
        if (secret.isEmpty()) return null

        val cleanName = if (issuer.isNotEmpty() && name.startsWith("$issuer:")) {
            name.removePrefix("$issuer:").trim()
        } else name
        return OtpAccount(
            name = cleanName,
            issuer = issuer,
            secretBase32 = base32Encode(secret),
            algorithm = when (algorithm) {
                2 -> "SHA256"
                3 -> "SHA512"
                4 -> "MD5"
                else -> "SHA1"
            },
            digits = if (digits == 2) 8 else 6,
            type = if (type == 1) "hotp" else "totp",
            counter = counter
        )
    }

    fun merge(existing: List<OtpAccount>, incoming: List<OtpAccount>): List<OtpAccount> {
        val seen = existing.map { it.secretBase32 to it.name }.toMutableSet()
        return existing + incoming.filter { seen.add(it.secretBase32 to it.name) }
    }

    fun toOtpAuthUri(a: OtpAccount): String {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val label = if (a.issuer.isNotEmpty()) "${enc(a.issuer)}:${enc(a.name)}" else enc(a.name)
        val params = buildList {
            add("secret=${a.secretBase32}")
            if (a.issuer.isNotEmpty()) add("issuer=${enc(a.issuer)}")
            add("algorithm=${a.algorithm}")
            add("digits=${a.digits}")
            if (a.type == "hotp") add("counter=${a.counter}") else add("period=${a.period}")
        }
        return "otpauth://${a.type}/$label?${params.joinToString("&")}"
    }

    fun toOtpAuthList(accounts: List<OtpAccount>): String =
        accounts.joinToString(separator = "\n", postfix = "\n") { toOtpAuthUri(it) }

    fun toAegisPlainJson(accounts: List<OtpAccount>): String {
        val entries: JsonArray = buildJsonArray {
            accounts.forEach { a ->
                add(buildJsonObject {
                    put("type", a.type)
                    put("uuid", UUID.nameUUIDFromBytes("${a.issuer}|${a.name}|${a.secretBase32}".toByteArray()).toString())
                    put("name", a.name)
                    put("issuer", a.issuer)
                    put("note", "Imported by Export To Graphene")
                    put("favorite", false)
                    put("icon", JsonNull)
                    put("info", buildJsonObject {
                        put("secret", a.secretBase32)
                        put("algo", a.algorithm)
                        put("digits", a.digits)
                        if (a.type == "hotp") put("counter", a.counter) else put("period", a.period)
                    })
                })
            }
        }
        val root = buildJsonObject {
            put("version", 1)
            put("header", buildJsonObject {
                put("slots", JsonNull)
                put("params", JsonNull)
            })
            put("db", buildJsonObject {
                put("version", JsonPrimitive(2))
                put("entries", entries)
            })
        }
        return root.toString()
    }

    fun base32Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(alphabet[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    private class ProtoReader(private val buf: ByteArray) {
        private var pos = 0

        fun hasMore() = pos < buf.size

        fun readTag(): Pair<Int, Int> {
            val tag = readVarint().toInt()
            return (tag ushr 3) to (tag and 0x7)
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < buf.size) { "Truncated varint" }
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "Varint too long" }
            }
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            require(len >= 0 && pos + len <= buf.size) { "Truncated length-delimited field" }
            return buf.copyOfRange(pos, pos + len).also { pos += len }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> readBytes()
                5 -> pos += 4
                else -> throw IllegalArgumentException("Unsupported wire type $wire")
            }
        }
    }
}
