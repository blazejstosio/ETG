package org.etg.migration.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

class OtpMigrationCodecTest {
    private fun ByteArrayOutputStream.varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { write(x.toInt()); return }
            write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }
    private fun ByteArrayOutputStream.tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    private fun ByteArrayOutputStream.bytesField(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); write(b) }
    private fun ByteArrayOutputStream.intField(field: Int, v: Long) { tag(field, 0); varint(v) }

    private fun otp(secret: ByteArray, name: String, issuer: String, algo: Int, digits: Int, type: Int, counter: Long = 0) =
        ByteArrayOutputStream().apply {
            bytesField(1, secret)
            bytesField(2, name.toByteArray())
            bytesField(3, issuer.toByteArray())
            intField(4, algo.toLong())
            intField(5, digits.toLong())
            intField(6, type.toLong())
            if (counter != 0L) intField(7, counter)
        }.toByteArray()

    private fun migrationUri(vararg otps: ByteArray, batchSize: Int = 1, batchIndex: Int = 0): String {
        val payload = ByteArrayOutputStream().apply {
            otps.forEach { bytesField(1, it) }
            intField(2, 1)
            intField(3, batchSize.toLong())
            intField(4, batchIndex.toLong())
            intField(5, 12345)
        }.toByteArray()
        val b64 = Base64.getEncoder().encodeToString(payload)
        return "otpauth-migration://offline?data=" + URLEncoder.encode(b64, "UTF-8")
    }

    private val helloSecret = "Hello!".toByteArray() + byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

    @Test
    fun base32MatchesRfcExample() {
        assertEquals("JBSWY3DPEHPK3PXP", OtpMigrationCodec.base32Encode(helloSecret))
        assertEquals("MZXW6YQ", OtpMigrationCodec.base32Encode("foob".toByteArray()))
    }

    @Test
    fun parsesTotpAndHotpAccounts() {
        val uri = migrationUri(
            otp(helloSecret, "GitHub:alice@example.com", "GitHub", algo = 1, digits = 1, type = 2),
            otp(byteArrayOf(1, 2, 3, 4, 5), "bob", "", algo = 2, digits = 2, type = 1, counter = 7),
            batchSize = 2,
            batchIndex = 1
        )
        val batch = OtpMigrationCodec.parse(uri)!!
        assertEquals(2, batch.batchSize)
        assertEquals(1, batch.batchIndex)
        assertEquals(2, batch.accounts.size)

        val gh = batch.accounts[0]
        assertEquals("alice@example.com", gh.name)
        assertEquals("GitHub", gh.issuer)
        assertEquals("JBSWY3DPEHPK3PXP", gh.secretBase32)
        assertEquals("SHA1", gh.algorithm)
        assertEquals(6, gh.digits)
        assertEquals("totp", gh.type)

        val bob = batch.accounts[1]
        assertEquals("hotp", bob.type)
        assertEquals("SHA256", bob.algorithm)
        assertEquals(8, bob.digits)
        assertEquals(7L, bob.counter)
    }

    @Test
    fun buildsOtpAuthUriAndAegisJson() {
        val acc = OtpMigrationCodec.parse(
            migrationUri(otp(helloSecret, "alice@example.com", "Git Hub", 1, 1, 2))
        )!!.accounts.single()
        assertEquals(
            "otpauth://totp/Git%20Hub:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Git%20Hub&algorithm=SHA1&digits=6&period=30",
            OtpMigrationCodec.toOtpAuthUri(acc)
        )
        val json = OtpMigrationCodec.toAegisPlainJson(listOf(acc))
        assertTrue(json.contains("\"secret\":\"JBSWY3DPEHPK3PXP\""))
        assertTrue(json.contains("\"slots\":null"))
    }

    @Test
    fun mergeDeduplicatesAcrossBatches() {
        val a = OtpMigrationCodec.parse(migrationUri(otp(helloSecret, "a", "X", 1, 1, 2)))!!.accounts
        assertEquals(1, OtpMigrationCodec.merge(a, a).size)
    }

    @Test
    fun rejectsOtherQrCodes() {
        assertFalse(OtpMigrationCodec.isMigrationUri("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(OtpMigrationCodec.parse("otpauth-migration://offline?data=%%%"))
        assertNull(OtpMigrationCodec.parse("https://example.com"))
    }
}
