package org.etg.migration.data.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TransferProtocolTest {
    private val payload = ByteArray(180_000) { (it * 13 + 7).toByte() }

    private fun runSender(
        session: TransferProtocol.SenderSession,
        approve: Boolean = true,
        onReady: (port: Int) -> Unit
    ): PeerRequest? {
        val seen = AtomicReference<PeerRequest?>()
        val ss = ServerSocket(0)
        val t = thread {
            ss.use {
                val client = it.accept()
                client.use { sock ->
                    runCatching {
                        TransferProtocol.handleSenderSession(
                            socket = sock,
                            session = session,
                            headerInfo = TransferHeaderInfo("test_backup.etg", payload.size.toLong(), "Pixel 8"),
                            confirm = { req -> seen.set(req); approve },
                            onClientConnected = {},
                            onProgress = {},
                            payloadWriter = { out -> out.write(payload) }
                        )
                    }
                }
            }
        }
        try {
            onReady(ss.localPort)
        } finally {
            t.join(5000)
        }
        return seen.get()
    }

    @Test
    fun pairingUriRoundTrip() {
        val session = TransferProtocol.newSenderSession("Pixel 8")
        val target = TransferProtocol.parsePairingUri(session.pairingUri("192.168.49.1", 47821))
        assertNotNull(target)
        assertEquals("192.168.49.1", target!!.host)
        assertEquals(47821, target.port)
        assertArrayEquals(session.token, target.token)
        assertArrayEquals(session.fingerprint, target.senderFingerprint)
        assertEquals("Pixel 8", target.deviceName)
        assertNull(TransferProtocol.parsePairingUri("etg://transfer?host=1.2.3.4&pin=123456"))
    }

    @Test
    fun shortCodeFormatting() {
        val code = TransferProtocol.generateShortCode()
        assertEquals(TransferProtocol.CODE_LENGTH, code.length)
        assertTrue(TransferProtocol.isValidShortCode(TransferProtocol.formatShortCode(code).lowercase()))
        assertFalse(TransferProtocol.isValidShortCode("ABC"))
    }

    @Test
    fun qrTokenTransferWithMatchingVerificationCodes() {
        val session = TransferProtocol.newSenderSession("Pixel 8")
        val receiverSas = AtomicReference<String>()
        val out = ByteArrayOutputStream()
        val request = runSender(session) { port ->
            val header = TransferProtocol.receiveFromSender(
                host = "127.0.0.1",
                port = port,
                auth = ClientAuth.Token(session.token, session.fingerprint),
                deviceName = "Pixel 9",
                outputFactory = { out },
                onAwaitingApproval = { sas, _ -> receiverSas.set(sas) }
            )
            assertEquals("test_backup.etg", header.fileName)
        }
        assertArrayEquals(payload, out.toByteArray())
        assertEquals("Pixel 9", request!!.deviceName)
        assertFalse(request.pairedViaCode)
        assertEquals(request.verificationCode, receiverSas.get())
    }

    @Test
    fun typedCodeTransfer() {
        val session = TransferProtocol.newSenderSession("Pixel 8")
        val out = ByteArrayOutputStream()
        val typed = TransferProtocol.formatShortCode(session.shortCode).lowercase()
        val request = runSender(session) { port ->
            TransferProtocol.receiveFromSender(
                "127.0.0.1", port, ClientAuth.Code(typed), "Pixel 9", { out }
            )
        }
        assertTrue(request!!.pairedViaCode)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test(expected = TransferAuthException::class)
    fun wrongCodeRejected() {
        val session = TransferProtocol.newSenderSession("Pixel 8")
        runSender(session) { port ->
            TransferProtocol.receiveFromSender(
                "127.0.0.1", port, ClientAuth.Code("ZZZZZZZZ"), "Attacker", { ByteArrayOutputStream() }
            )
        }
    }

    @Test(expected = TransferAuthException::class)
    fun substitutedSenderKeyDetected() {
        val real = TransferProtocol.newSenderSession("Pixel 8")
        val impostor = TransferProtocol.newSenderSession("Pixel 8")

        val forged = TransferProtocol.SenderSession(impostor.keyPair, real.token, real.shortCode, "Pixel 8")
        runSender(forged) { port ->
            TransferProtocol.receiveFromSender(
                "127.0.0.1", port, ClientAuth.Token(real.token, real.fingerprint), "Pixel 9", { ByteArrayOutputStream() }
            )
        }
    }

    @Test(expected = TransferRejectedException::class)
    fun ownerDeclinesTransfer() {
        val session = TransferProtocol.newSenderSession("Pixel 8")
        runSender(session, approve = false) { port ->
            TransferProtocol.receiveFromSender(
                "127.0.0.1", port, ClientAuth.Token(session.token, session.fingerprint), "Pixel 9", { ByteArrayOutputStream() }
            )
        }
    }
}
