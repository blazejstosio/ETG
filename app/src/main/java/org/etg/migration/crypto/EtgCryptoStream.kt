package org.etg.migration.crypto

import java.io.EOFException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EtgCryptoFormat {
    val MAGIC = byteArrayOf(0x45, 0x54, 0x47, 0x31)
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 36
    const val SALT_SIZE = 16
    const val NONCE_SIZE = 12
    const val KEY_BITS = 256
    const val PBKDF2_ITERATIONS = 210_000
    const val TRANSFER_PIN_ITERATIONS = 60_000
    const val CHUNK_SIZE = 64 * 1024
    const val GCM_TAG_BITS = 128
    const val GCM_TAG_BYTES = 16

    fun isEncryptedEtgHeader(prefix: ByteArray): Boolean {
        if (prefix.size < HEADER_SIZE) return false
        return prefix[0] == MAGIC[0] &&
            prefix[1] == MAGIC[1] &&
            prefix[2] == MAGIC[2] &&
            prefix[3] == MAGIC[3] &&
            prefix[4] == VERSION
    }

    fun deriveArchiveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun deriveTransferKey(pin: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, TRANSFER_PIN_ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    internal fun buildChunkNonce(baseNonce: ByteArray, chunkIndex: Long): ByteArray {
        val nonce = baseNonce.copyOf()
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            val idxByte = ((chunkIndex ushr shift) and 0xFFL).toByte()
            nonce[4 + i] = (nonce[4 + i].toInt() xor idxByte.toInt()).toByte()
        }
        return nonce
    }

    internal fun buildChunkAad(headerBytes: ByteArray, chunkIndex: Long, isFinal: Boolean): ByteArray {
        return ByteBuffer.allocate(headerBytes.size + 9)
            .put(headerBytes)
            .putLong(chunkIndex)
            .put(if (isFinal) 1.toByte() else 0.toByte())
            .array()
    }
}

class EtgChunkedCipherOutputStream(
    delegate: OutputStream,
    private val secretKey: SecretKeySpec,
    private val headerBytes: ByteArray,
    private val baseNonce: ByteArray
) : FilterOutputStream(delegate) {
    constructor(
        delegate: OutputStream,
        password: String,
        random: SecureRandom = SecureRandom()
    ) : this(
        delegate = delegate,
        initParams = createHeaderAndKey(password.toCharArray(), random)
    )

    private constructor(
        delegate: OutputStream,
        initParams: Triple<SecretKeySpec, ByteArray, ByteArray>
    ) : this(delegate, initParams.first, initParams.second, initParams.third)

    private val buffer = ByteArray(EtgCryptoFormat.CHUNK_SIZE)
    private var buffered = 0
    private var chunkIndex = 0L
    private var headerWritten = false
    private var closed = false

    companion object {
        private fun createHeaderAndKey(
            password: CharArray,
            random: SecureRandom
        ): Triple<SecretKeySpec, ByteArray, ByteArray> {
            val salt = ByteArray(EtgCryptoFormat.SALT_SIZE).also { random.nextBytes(it) }
            val nonce = ByteArray(EtgCryptoFormat.NONCE_SIZE).also { random.nextBytes(it) }
            val header = ByteBuffer.allocate(EtgCryptoFormat.HEADER_SIZE)
                .put(EtgCryptoFormat.MAGIC)
                .put(EtgCryptoFormat.VERSION)
                .put(byteArrayOf(0, 0, 0))
                .put(salt)
                .put(nonce)
                .array()
            val key = EtgCryptoFormat.deriveArchiveKey(password, salt)
            password.fill('\u0000')
            return Triple(key, header, nonce)
        }

        fun withPreDerivedKey(
            delegate: OutputStream,
            key: SecretKeySpec,
            random: SecureRandom = SecureRandom()
        ): EtgChunkedCipherOutputStream {
            val salt = ByteArray(EtgCryptoFormat.SALT_SIZE).also { random.nextBytes(it) }
            val nonce = ByteArray(EtgCryptoFormat.NONCE_SIZE).also { random.nextBytes(it) }
            val header = ByteBuffer.allocate(EtgCryptoFormat.HEADER_SIZE)
                .put(EtgCryptoFormat.MAGIC)
                .put(EtgCryptoFormat.VERSION)
                .put(byteArrayOf(0, 0, 0))
                .put(salt)
                .put(nonce)
                .array()
            return EtgChunkedCipherOutputStream(delegate, key, header, nonce)
        }
    }

    private fun ensureHeaderWritten() {
        if (!headerWritten) {
            out.write(headerBytes)
            headerWritten = true
        }
    }

    override fun write(b: Int) {
        if (closed) throw IOException("Stream already closed")
        if (buffered == buffer.size) {
            flushChunk(isFinal = false)
        }
        buffer[buffered++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("Stream already closed")
        if (off < 0 || len < 0 || off + len > b.size) {
            throw IndexOutOfBoundsException("Invalid offset/length")
        }
        var offset = off
        var remaining = len
        while (remaining > 0) {
            if (buffered == buffer.size) {
                flushChunk(isFinal = false)
            }
            val toCopy = minOf(remaining, buffer.size - buffered)
            System.arraycopy(b, offset, buffer, buffered, toCopy)
            buffered += toCopy
            offset += toCopy
            remaining -= toCopy
        }
    }

    private fun flushChunk(isFinal: Boolean) {
        ensureHeaderWritten()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val chunkNonce = EtgCryptoFormat.buildChunkNonce(baseNonce, chunkIndex)
        val aad = EtgCryptoFormat.buildChunkAad(headerBytes, chunkIndex, isFinal)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            GCMParameterSpec(EtgCryptoFormat.GCM_TAG_BITS, chunkNonce)
        )
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(buffer, 0, buffered)
        val frameHeader = ByteBuffer.allocate(5)
            .put(if (isFinal) 1.toByte() else 0.toByte())
            .putInt(encrypted.size)
            .array()
        out.write(frameHeader)
        out.write(encrypted)
        buffered = 0
        chunkIndex++
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            flushChunk(isFinal = true)
            out.flush()
        } finally {
            out.close()
        }
    }
}

class EtgChunkedCipherInputStream private constructor(
    delegate: InputStream,
    private val secretKey: SecretKeySpec,
    private val headerBytes: ByteArray,
    private val baseNonce: ByteArray
) : FilterInputStream(delegate) {
    companion object {
        fun open(input: InputStream, password: String): EtgChunkedCipherInputStream {
            val header = readExactOrThrow(input, EtgCryptoFormat.HEADER_SIZE)
            if (!EtgCryptoFormat.isEncryptedEtgHeader(header)) {
                throw IOException("Invalid ETG encrypted stream header")
            }
            val salt = header.copyOfRange(8, 8 + EtgCryptoFormat.SALT_SIZE)
            val nonce = header.copyOfRange(
                8 + EtgCryptoFormat.SALT_SIZE,
                8 + EtgCryptoFormat.SALT_SIZE + EtgCryptoFormat.NONCE_SIZE
            )
            val chars = password.toCharArray()
            val key = EtgCryptoFormat.deriveArchiveKey(chars, salt)
            chars.fill('\u0000')
            return EtgChunkedCipherInputStream(input, key, header, nonce)
        }

        fun openWithPreDerivedKey(input: InputStream, key: SecretKeySpec): EtgChunkedCipherInputStream {
            val header = readExactOrThrow(input, EtgCryptoFormat.HEADER_SIZE)
            if (!EtgCryptoFormat.isEncryptedEtgHeader(header)) {
                throw IOException("Invalid ETG encrypted stream header")
            }
            val nonce = header.copyOfRange(
                8 + EtgCryptoFormat.SALT_SIZE,
                8 + EtgCryptoFormat.SALT_SIZE + EtgCryptoFormat.NONCE_SIZE
            )
            return EtgChunkedCipherInputStream(input, key, header, nonce)
        }

        private fun readExactOrThrow(input: InputStream, size: Int): ByteArray {
            val out = ByteArray(size)
            var total = 0
            while (total < size) {
                val read = input.read(out, total, size - total)
                if (read < 0) throw EOFException("Unexpected EOF while reading ETG header")
                total += read
            }
            return out
        }
    }

    private var currentPlain: ByteArray = ByteArray(0)
    private var currentPos = 0
    private var chunkIndex = 0L
    private var seenFinal = false

    private fun readNextChunk(): Boolean {
        if (seenFinal) return false
        val frameHeader = ByteArray(5)
        var readHeader = 0
        while (readHeader < 5) {
            val n = `in`.read(frameHeader, readHeader, 5 - readHeader)
            if (n < 0) {
                throw EOFException("Truncated encrypted ETG stream: missing final chunk")
            }
            readHeader += n
        }
        val isFinal = frameHeader[0] == 1.toByte()
        if (frameHeader[0] != 0.toByte() && frameHeader[0] != 1.toByte()) {
            throw IOException("Corrupted ETG chunk flag")
        }
        val cipherLen = ByteBuffer.wrap(frameHeader, 1, 4).int
        if (cipherLen < EtgCryptoFormat.GCM_TAG_BYTES ||
            cipherLen > EtgCryptoFormat.CHUNK_SIZE + EtgCryptoFormat.GCM_TAG_BYTES
        ) {
            throw IOException("Invalid ETG chunk length: $cipherLen")
        }
        val cipherBytes = ByteArray(cipherLen)
        var total = 0
        while (total < cipherLen) {
            val n = `in`.read(cipherBytes, total, cipherLen - total)
            if (n < 0) {
                throw EOFException("Truncated encrypted ETG chunk payload")
            }
            total += n
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = EtgCryptoFormat.buildChunkNonce(baseNonce, chunkIndex)
        val aad = EtgCryptoFormat.buildChunkAad(headerBytes, chunkIndex, isFinal)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(EtgCryptoFormat.GCM_TAG_BITS, nonce)
        )
        cipher.updateAAD(aad)
        currentPlain = cipher.doFinal(cipherBytes)
        currentPos = 0
        chunkIndex++
        if (isFinal) {
            seenFinal = true
            val extra = `in`.read()
            if (extra != -1) {
                throw IOException("Trailing unauthenticated bytes after final ETG chunk")
            }
        }
        return true
    }

    override fun read(): Int {
        while (currentPos >= currentPlain.size) {
            if (!readNextChunk()) return -1
        }
        return currentPlain[currentPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > b.size) {
            throw IndexOutOfBoundsException("Invalid offset/length")
        }
        if (len == 0) return 0
        while (currentPos >= currentPlain.size) {
            if (!readNextChunk()) return -1
        }
        val available = currentPlain.size - currentPos
        val toCopy = minOf(len, available)
        System.arraycopy(currentPlain, currentPos, b, off, toCopy)
        currentPos += toCopy
        return toCopy
    }
}

object Sha256Helper {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return toHex(digest)
    }

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return toHex(md.digest())
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}

class HashingOutputStream(delegate: OutputStream) : FilterOutputStream(delegate) {
    private val md = MessageDigest.getInstance("SHA-256")
    var bytesWritten: Long = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        md.update(b.toByte())
        bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        md.update(b, off, len)
        bytesWritten += len
    }

    fun sha256Hex(): String = Sha256Helper.toHex(md.digest())
}
