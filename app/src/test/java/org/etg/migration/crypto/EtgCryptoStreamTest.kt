package org.etg.migration.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class EtgCryptoStreamTest {
    @Test
    fun roundTripMultiChunkPayload() {
        val random = SecureRandom()
        val payload = ByteArray(250_000) { (it * 31).toByte() }
        val encodedOut = ByteArrayOutputStream()

        EtgChunkedCipherOutputStream(encodedOut, "GrapheneStrongPass!2026", random).use { out ->
            out.write(payload)
        }

        val cipherBytes = encodedOut.toByteArray()
        assertTrue(EtgCryptoFormat.isEncryptedEtgHeader(cipherBytes))

        val decoded = EtgChunkedCipherInputStream.open(
            ByteArrayInputStream(cipherBytes),
            "GrapheneStrongPass!2026"
        ).use { it.readBytes() }

        assertArrayEquals(payload, decoded)
    }

    @Test
    fun emptyPayloadRoundTrip() {
        val encodedOut = ByteArrayOutputStream()
        EtgChunkedCipherOutputStream(encodedOut, "test1234").use { }
        val cipherBytes = encodedOut.toByteArray()

        val decoded = EtgChunkedCipherInputStream.open(
            ByteArrayInputStream(cipherBytes),
            "test1234"
        ).use { it.readBytes() }

        assertArrayEquals(ByteArray(0), decoded)
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongPasswordRejected() {
        val encodedOut = ByteArrayOutputStream()
        EtgChunkedCipherOutputStream(encodedOut, "correct-password").use { out ->
            out.write("Secret Graphene Data".toByteArray())
        }

        EtgChunkedCipherInputStream.open(
            ByteArrayInputStream(encodedOut.toByteArray()),
            "wrong-password"
        ).use { it.readBytes() }
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedCiphertextRejected() {
        val encodedOut = ByteArrayOutputStream()
        EtgChunkedCipherOutputStream(encodedOut, "pass1234").use { out ->
            out.write("Integrity protected payload".toByteArray())
        }

        val tampered = encodedOut.toByteArray()
        tampered[tampered.size - 10] = (tampered[tampered.size - 10].toInt() xor 0xFF).toByte()

        EtgChunkedCipherInputStream.open(
            ByteArrayInputStream(tampered),
            "pass1234"
        ).use { it.readBytes() }
    }

    @Test(expected = EOFException::class)
    fun truncatedStreamRejected() {
        val encodedOut = ByteArrayOutputStream()
        EtgChunkedCipherOutputStream(encodedOut, "pass1234").use { out ->
            out.write(ByteArray(150_000) { 0x5A })
        }

        val full = encodedOut.toByteArray()
        val truncated = full.copyOf(EtgCryptoFormat.HEADER_SIZE + 100)

        EtgChunkedCipherInputStream.open(
            ByteArrayInputStream(truncated),
            "pass1234"
        ).use { it.readBytes() }
    }

    @Test(expected = IOException::class)
    fun trailingGarbageAfterFinalChunkRejected() {
        val encodedOut = ByteArrayOutputStream()
        EtgChunkedCipherOutputStream(encodedOut, "pass1234").use { out ->
            out.write("hello".toByteArray())
        }

        val withTrailing = encodedOut.toByteArray() + byteArrayOf(0x01, 0x02, 0x03)
        EtgChunkedCipherInputStream.open(
            ByteArrayInputStream(withTrailing),
            "pass1234"
        ).use { it.readBytes() }
    }

    @Test
    fun sha256AndConstantTimeCompare() {
        val h1 = Sha256Helper.sha256Hex("graphene".toByteArray())
        val h2 = Sha256Helper.sha256Hex("graphene".toByteArray())
        val h3 = Sha256Helper.sha256Hex("android".toByteArray())
        assertTrue(h1 == h2)
        assertFalse(h1 == h3)
        assertTrue(Sha256Helper.constantTimeEquals("123456".toByteArray(), "123456".toByteArray()))
        assertFalse(Sha256Helper.constantTimeEquals("123456".toByteArray(), "123457".toByteArray()))
    }
}
