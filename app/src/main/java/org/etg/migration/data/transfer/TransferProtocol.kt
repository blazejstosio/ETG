package org.etg.migration.data.transfer

import org.etg.migration.crypto.EtgChunkedCipherInputStream
import org.etg.migration.crypto.EtgChunkedCipherOutputStream
import org.etg.migration.crypto.Sha256Helper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.spec.SecretKeySpec

data class TransferHeaderInfo(
    val fileName: String,
    val estimatedBytes: Long,
    val deviceName: String
)

data class TransferProgressStats(
    val bytesTransferred: Long,
    val totalBytes: Long,
    val bytesPerSec: Long,
    val etaSeconds: Long
)

data class PeerRequest(
    val deviceName: String,
    val verificationCode: String,
    val pairedViaCode: Boolean
)

class TransferAuthException(message: String) : IOException(message)
class TransferRejectedException(message: String) : IOException(message)

sealed class ClientAuth {
    data class Token(val token: ByteArray, val senderFingerprint: ByteArray) : ClientAuth()

    data class Code(val code: String) : ClientAuth()
}

data class PairingTarget(
    val host: String,
    val port: Int,
    val token: ByteArray,
    val senderFingerprint: ByteArray,
    val deviceName: String
)

object TransferProtocol {
    const val DEFAULT_PORT = 47821
    const val MAX_CODE_FAILURES = 5
    private const val CONFIRM_TIMEOUT_MS = 120_000L
    private val WIRE_MAGIC = "ETGTR2".toByteArray(StandardCharsets.US_ASCII)
    private const val MODE_TOKEN = 1
    private const val MODE_CODE = 2
    private const val STATUS_BAD_PROOF = 0x00
    private const val STATUS_PROOF_OK = 0x01
    private const val STATUS_ACCEPTED = 0x02
    private const val STATUS_REJECTED = 0x03

    private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val CODE_LENGTH = 8

    class SenderSession internal constructor(
        internal val keyPair: KeyPair,
        val token: ByteArray,
        val shortCode: String,
        val deviceName: String
    ) {
        val publicKeyBytes: ByteArray get() = keyPair.public.encoded
        val fingerprint: ByteArray get() = fingerprintOf(publicKeyBytes)

        fun pairingUri(host: String, port: Int): String =
            "etg://pair?v=2" +
                "&h=${urlEncode(host)}" +
                "&p=$port" +
                "&k=${b64(token)}" +
                "&f=${b64(fingerprint)}" +
                "&n=${urlEncode(deviceName.take(40))}"
    }

    fun newSenderSession(deviceName: String, random: SecureRandom = SecureRandom()): SenderSession {
        val token = ByteArray(16).also { random.nextBytes(it) }
        return SenderSession(generateKeyPair(random), token, generateShortCode(random), deviceName)
    }

    fun generateShortCode(random: SecureRandom = SecureRandom()): String =
        buildString { repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }

    fun formatShortCode(code: String): String =
        if (code.length == CODE_LENGTH) "${code.substring(0, 4)}-${code.substring(4)}" else code

    fun normalizeShortCode(input: String): String =
        input.uppercase().filter { it.isLetterOrDigit() }

    fun isValidShortCode(input: String): Boolean {
        val n = normalizeShortCode(input)
        return n.length == CODE_LENGTH && n.all { it in CODE_ALPHABET }
    }

    fun generatePin(random: SecureRandom = SecureRandom()): String = "%06d".format(random.nextInt(1_000_000))

    fun parsePairingUri(raw: String): PairingTarget? {
        val s = raw.trim()
        if (!s.startsWith("etg://pair?", ignoreCase = true)) return null
        val params = s.substringAfter('?').split('&').associate {
            it.substringBefore('=') to urlDecode(it.substringAfter('=', ""))
        }
        if (params["v"] != "2") return null
        val host = params["h"]?.takeIf { it.isNotBlank() } ?: return null
        val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        val token = params["k"]?.let { unb64(it) }?.takeIf { it.size == 16 } ?: return null
        val fp = params["f"]?.let { unb64(it) }?.takeIf { it.size == 16 } ?: return null
        return PairingTarget(host, port, token, fp, params["n"].orEmpty())
    }

    fun serveSingleTransfer(
        serverSocket: ServerSocket,
        session: SenderSession,
        headerInfo: TransferHeaderInfo,
        confirm: (PeerRequest) -> Boolean,
        isCancelled: () -> Boolean = { false },
        onClientConnected: () -> Unit = {},
        onProgress: (TransferProgressStats) -> Unit = {},
        payloadWriter: (OutputStream) -> Unit
    ) {
        var codeFailures = 0
        while (!isCancelled()) {
            val socket = serverSocket.accept()
            val outcome = socket.use { client ->
                client.soTimeout = 30_000
                client.tcpNoDelay = true
                runCatching {
                    handleSenderSession(client, session, headerInfo, confirm, onClientConnected, onProgress, payloadWriter)
                }.getOrElse { err ->

                    if (err is IOException) SessionOutcome.ABORTED else throw err
                }
            }
            when (outcome) {
                SessionOutcome.COMPLETED -> return
                SessionOutcome.BAD_CODE -> if (++codeFailures >= MAX_CODE_FAILURES) {
                    throw TransferAuthException("Too many wrong pairing codes – session locked")
                }
                SessionOutcome.BAD_TOKEN, SessionOutcome.REJECTED, SessionOutcome.ABORTED -> Unit
            }
        }
    }

    internal enum class SessionOutcome { COMPLETED, BAD_CODE, BAD_TOKEN, REJECTED, ABORTED }

    internal fun handleSenderSession(
        socket: Socket,
        session: SenderSession,
        headerInfo: TransferHeaderInfo,
        confirm: (PeerRequest) -> Boolean,
        onClientConnected: () -> Unit,
        onProgress: (TransferProgressStats) -> Unit,
        payloadWriter: (OutputStream) -> Unit
    ): SessionOutcome {
        val rawOut = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val dataOut = DataOutputStream(rawOut)
        val dataIn = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))

        val senderName = session.deviceName.toByteArray(StandardCharsets.UTF_8)
        dataOut.write(WIRE_MAGIC)
        writeFrame(dataOut, session.publicKeyBytes)
        writeFrame(dataOut, senderName)
        dataOut.flush()

        val mode = dataIn.readUnsignedByte()
        val clientPub = readFrame(dataIn, 512)
        val clientName = readFrame(dataIn, 256)
        val clientProof = ByteArray(32).also { dataIn.readFully(it) }

        val secret = when (mode) {
            MODE_TOKEN -> session.token
            MODE_CODE -> session.shortCode.toByteArray(StandardCharsets.US_ASCII)
            else -> return SessionOutcome.ABORTED
        }
        val transcript = transcriptHash(session.publicKeyBytes, senderName, mode, clientPub, clientName)
        val keys = runCatching {
            deriveKeys(session.keyPair, decodePublicKey(clientPub), secret, transcript)
        }.getOrElse { return SessionOutcome.ABORTED }

        if (!Sha256Helper.constantTimeEquals(clientProof, mac(keys.auth, "C", transcript))) {
            dataOut.writeByte(STATUS_BAD_PROOF)
            dataOut.flush()
            return if (mode == MODE_CODE) SessionOutcome.BAD_CODE else SessionOutcome.BAD_TOKEN
        }
        dataOut.writeByte(STATUS_PROOF_OK)
        dataOut.write(mac(keys.auth, "S", transcript))
        dataOut.flush()

        socket.soTimeout = (CONFIRM_TIMEOUT_MS + 10_000).toInt()
        val approved = runCatching {
            confirm(
                PeerRequest(
                    deviceName = String(clientName, StandardCharsets.UTF_8).ifBlank { "Android" },
                    verificationCode = keys.verificationCode,
                    pairedViaCode = mode == MODE_CODE
                )
            )
        }.getOrDefault(false)
        if (!approved) {
            dataOut.writeByte(STATUS_REJECTED)
            dataOut.flush()
            return SessionOutcome.REJECTED
        }
        dataOut.writeByte(STATUS_ACCEPTED)

        val metaBytes = listOf(headerInfo.fileName, headerInfo.estimatedBytes.toString(), headerInfo.deviceName)
            .joinToString("\n") { it.replace("\n", " ").replace("\r", "") }
            .toByteArray(StandardCharsets.UTF_8)
        writeFrame(dataOut, metaBytes)
        dataOut.flush()

        onClientConnected()
        socket.soTimeout = 120_000

        val startMs = System.currentTimeMillis()
        var totalSent = 0L
        val countingOut = object : OutputStream() {
            override fun write(b: Int) {
                rawOut.write(b)
                totalSent++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                rawOut.write(b, off, len)
                totalSent += len
                val elapsed = maxOf(1L, (System.currentTimeMillis() - startMs) / 1000L)
                val speed = totalSent / elapsed
                val eta = if (speed > 0L && headerInfo.estimatedBytes > totalSent) {
                    (headerInfo.estimatedBytes - totalSent) / speed
                } else -1L
                onProgress(TransferProgressStats(totalSent, headerInfo.estimatedBytes, speed, eta))
            }

            override fun flush() = rawOut.flush()
            override fun close() = rawOut.close()
        }

        EtgChunkedCipherOutputStream.withPreDerivedKey(countingOut, keys.encryption).use { encOut ->
            payloadWriter(encOut)
        }
        return SessionOutcome.COMPLETED
    }

    fun receiveFromSender(
        host: String,
        port: Int,
        auth: ClientAuth,
        deviceName: String,
        outputFactory: (TransferHeaderInfo) -> OutputStream,
        onAwaitingApproval: (verificationCode: String, senderName: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgressStats) -> Unit = {},
        connectTimeoutMs: Int = 15_000
    ): TransferHeaderInfo {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = 30_000
        socket.tcpNoDelay = true

        return socket.use { s ->
            val dataOut = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            val rawIn = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val dataIn = DataInputStream(rawIn)

            val magic = ByteArray(WIRE_MAGIC.size).also { dataIn.readFully(it) }
            if (!magic.contentEquals(WIRE_MAGIC)) throw IOException("Not an ETG v2 sender")
            val senderPub = readFrame(dataIn, 512)
            val senderName = readFrame(dataIn, 256)

            val (mode, secret) = when (auth) {
                is ClientAuth.Token -> {
                    if (!Sha256Helper.constantTimeEquals(fingerprintOf(senderPub), auth.senderFingerprint)) {
                        throw TransferAuthException("Sender identity does not match the scanned QR / NFC code")
                    }
                    MODE_TOKEN to auth.token
                }
                is ClientAuth.Code -> MODE_CODE to normalizeShortCode(auth.code).toByteArray(StandardCharsets.US_ASCII)
            }

            val myKeys = generateKeyPair(SecureRandom())
            val myPub = myKeys.public.encoded
            val myName = deviceName.take(60).toByteArray(StandardCharsets.UTF_8)
            val transcript = transcriptHash(senderPub, senderName, mode, myPub, myName)
            val keys = deriveKeys(myKeys, decodePublicKey(senderPub), secret, transcript)

            dataOut.writeByte(mode)
            writeFrame(dataOut, myPub)
            writeFrame(dataOut, myName)
            dataOut.write(mac(keys.auth, "C", transcript))
            dataOut.flush()

            if (dataIn.readUnsignedByte() != STATUS_PROOF_OK) {
                throw TransferAuthException("Pairing code rejected by the sending phone")
            }
            val serverProof = ByteArray(32).also { dataIn.readFully(it) }
            if (!Sha256Helper.constantTimeEquals(serverProof, mac(keys.auth, "S", transcript))) {
                throw TransferAuthException("Sending phone failed key confirmation")
            }

            onAwaitingApproval(keys.verificationCode, String(senderName, StandardCharsets.UTF_8))
            s.soTimeout = (CONFIRM_TIMEOUT_MS + 15_000).toInt()
            when (dataIn.readUnsignedByte()) {
                STATUS_ACCEPTED -> Unit
                STATUS_REJECTED -> throw TransferRejectedException("Transfer declined on the sending phone")
                else -> throw IOException("Unexpected approval status")
            }

            val lines = String(readFrame(dataIn, 64 * 1024), StandardCharsets.UTF_8).split('\n')
            val header = TransferHeaderInfo(
                fileName = lines.getOrElse(0) { "etg_backup.etg" },
                estimatedBytes = lines.getOrElse(1) { "0" }.toLongOrNull() ?: 0L,
                deviceName = lines.getOrElse(2) { "Android" }
            )

            s.soTimeout = 120_000
            val startMs = System.currentTimeMillis()
            var received = 0L
            EtgChunkedCipherInputStream.openWithPreDerivedKey(rawIn, keys.encryption).use { decIn ->
                outputFactory(header).use { destOut ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("Transfer cancelled")
                        val n = decIn.read(buf)
                        if (n < 0) break
                        destOut.write(buf, 0, n)
                        received += n
                        val elapsed = maxOf(1L, (System.currentTimeMillis() - startMs) / 1000L)
                        val speed = received / elapsed
                        val eta = if (speed > 0L && header.estimatedBytes > received) {
                            (header.estimatedBytes - received) / speed
                        } else -1L
                        onProgress(TransferProgressStats(received, maxOf(header.estimatedBytes, received), speed, eta))
                    }
                    destOut.flush()
                }
            }
            header
        }
    }

    private class SessionKeys(val auth: ByteArray, val encryption: SecretKeySpec, val verificationCode: String)

    private fun generateKeyPair(random: SecureRandom): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    private fun decodePublicKey(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    internal fun fingerprintOf(publicKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(16)

    private fun transcriptHash(
        senderPub: ByteArray,
        senderName: ByteArray,
        mode: Int,
        clientPub: ByteArray,
        clientName: ByteArray
    ): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(WIRE_MAGIC)
        listOf(senderPub, senderName, byteArrayOf(mode.toByte()), clientPub, clientName).forEach {
            update(byteArrayOf((it.size ushr 8).toByte(), it.size.toByte()))
            update(it)
        }
        digest()
    }

    private fun deriveKeys(mine: KeyPair, peer: PublicKey, secret: ByteArray, transcript: ByteArray): SessionKeys {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(mine.private)
            doPhase(peer, true)
            generateSecret()
        }
        val salt = MessageDigest.getInstance("SHA-256").digest("ETG-PAIR-v2".toByteArray() + secret)
        val prk = Sha256Helper.hmacSha256(salt, shared)
        val okm = hkdfExpand(prk, transcript, 96)
        val sas = okm.copyOfRange(64, 96)
        val sasNum = ((sas[0].toLong() and 0xFF) shl 24 or ((sas[1].toLong() and 0xFF) shl 16) or
            ((sas[2].toLong() and 0xFF) shl 8) or (sas[3].toLong() and 0xFF)) % 1_000_000L
        val sasStr = "%06d".format(sasNum)
        return SessionKeys(
            auth = okm.copyOfRange(0, 32),
            encryption = SecretKeySpec(okm.copyOfRange(32, 64), "AES"),
            verificationCode = "${sasStr.substring(0, 3)} ${sasStr.substring(3)}"
        )
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var prev = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            prev = Sha256Helper.hmacSha256(prk, prev + info + byteArrayOf(counter.toByte()))
            out.write(prev)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun mac(key: ByteArray, label: String, transcript: ByteArray): ByteArray =
        Sha256Helper.hmacSha256(key, label.toByteArray(StandardCharsets.US_ASCII) + transcript)

    private fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readFrame(input: DataInputStream, max: Int): ByteArray {
        val len = input.readUnsignedShort()
        if (len > max) throw IOException("Frame too large: $len")
        return ByteArray(len).also { input.readFully(it) }
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(s: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()
    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    private fun urlDecode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
