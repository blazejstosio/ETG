package org.etg.migration.data.archive

import java.io.IOException
import org.etg.migration.model.IssuePhase
import org.etg.migration.model.TransferIssue
import android.content.Context
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.etg.migration.crypto.EtgChunkedCipherOutputStream
import org.etg.migration.crypto.HashingOutputStream
import org.etg.migration.data.exporter.AppsExporter
import org.etg.migration.data.exporter.AuthenticatorExporter
import org.etg.migration.data.exporter.CalendarExporter
import org.etg.migration.data.exporter.ContactsExporter
import org.etg.migration.data.exporter.MediaAndFilesExporter
import org.etg.migration.data.exporter.MessagesAndCallsExporter
import org.etg.migration.data.exporter.SettingsExporter
import org.etg.migration.data.exporter.WifiExporter
import org.etg.migration.model.ArchiveEntryMeta
import org.etg.migration.model.ArchiveManifest
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.ExportConfig
import org.etg.migration.model.GrapheneReadinessItem
import org.etg.migration.model.MediaFileEntry
import org.etg.migration.model.OperationPhase
import org.etg.migration.model.OperationProgress
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val EtgJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}

interface ArchiveSink : Closeable {
    val recordedEntries: List<ArchiveEntryMeta>
    val totalBytesWritten: Long

    val issues: IssueLog

    val failedEntries: List<String> get() = emptyList()

    val entryAliases: Map<String, String> get() = emptyMap()

    fun hasEntry(path: String): Boolean = recordedEntries.any { it.path == path.trimStart('/') }

    fun writeEntry(
        path: String,
        category: ExportCategory,
        compress: Boolean = true,
        writer: (OutputStream) -> Unit
    )

    fun writeBytes(
        path: String,
        category: ExportCategory,
        bytes: ByteArray,
        compress: Boolean = true
    ) {
        writeEntry(path, category, compress) { out ->
            out.write(bytes)
        }
    }

    fun copyFile(
        path: String,
        category: ExportCategory,
        file: File,
        compress: Boolean = false
    ) {
        file.inputStream().use { input ->
            copyStream(path, category, input, compress)
        }
    }

    fun copyStream(
        path: String,
        category: ExportCategory,
        input: InputStream,
        compress: Boolean = false
    ) {
        writeEntry(path, category, compress) { out ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                out.write(buf, 0, read)
            }
        }
    }
}

class ZipArchiveSink(
    rawOutput: OutputStream,
    encryptionPassword: String?,
    private val isCancelled: () -> Boolean = { false },
    private val onBytesProgress: (Long) -> Unit = {}
) : ArchiveSink {
    private val encryptedStream: OutputStream = if (!encryptionPassword.isNullOrBlank()) {
        EtgChunkedCipherOutputStream(BufferedOutputStream(rawOutput, 32 * 1024), encryptionPassword)
    } else {
        BufferedOutputStream(rawOutput, 32 * 1024)
    }

    private val zipOut = ZipOutputStream(BufferedOutputStream(encryptedStream, 32 * 1024))
    private val _entries = mutableListOf<ArchiveEntryMeta>()
    override val recordedEntries: List<ArchiveEntryMeta> get() = _entries
    override var totalBytesWritten: Long = 0L
        private set
    override val issues = IssueLog()
    private val written = hashSetOf<String>()
    override fun hasEntry(path: String): Boolean = path.trimStart('/') in written
    private val usedNames = hashSetOf<String>()
    private val _failed = mutableListOf<String>()
    private val _aliases = linkedMapOf<String, String>()
    override val failedEntries: List<String> get() = _failed
    override val entryAliases: Map<String, String> get() = _aliases

    private inline fun <T> dest(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw if (e is SinkBrokenException) e else SinkBrokenException(e)
    }

    override fun writeEntry(
        path: String,
        category: ExportCategory,
        compress: Boolean,
        writer: (OutputStream) -> Unit
    ) {
        if (isCancelled()) throw CancellationException("Export cancelled")
        val normalizedPath = path.trimStart('/')
        if (hasEntry(normalizedPath)) return

        var zipName = normalizedPath
        var n = 1
        while (zipName in usedNames) zipName = "$normalizedPath.etgretry${n++}"
        usedNames += zipName
        dest {
            zipOut.setLevel(if (compress) Deflater.DEFAULT_COMPRESSION else Deflater.NO_COMPRESSION)
            zipOut.putNextEntry(ZipEntry(zipName))
        }
        val nonClosingOut = object : OutputStream() {
            override fun write(b: Int) = dest { zipOut.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (isCancelled()) throw CancellationException("Export cancelled")
                dest { zipOut.write(b, off, len) }
            }
            override fun flush() = dest { zipOut.flush() }
        }
        val hashing = HashingOutputStream(nonClosingOut)
        try {
            writer(hashing)
            hashing.flush()
        } catch (t: Throwable) {
            if (IssueLog.isFatal(t)) throw t

            dest { zipOut.closeEntry() }
            _failed += zipName
            throw t
        }
        dest { zipOut.closeEntry() }
        if (zipName != normalizedPath) _aliases[zipName] = normalizedPath

        val size = hashing.bytesWritten
        totalBytesWritten += size
        written += normalizedPath
        _entries += ArchiveEntryMeta(
            path = normalizedPath,
            category = category,
            sizeBytes = size,
            sha256 = hashing.sha256Hex()
        )
        onBytesProgress(totalBytesWritten)
    }

    override fun close() {
        zipOut.finish()
        zipOut.close()
    }
}

class UsbFolderArchiveSink(
    private val context: Context,
    private val backupRootDir: DocumentFile,
    private val encryptionPassword: String?,
    private val isCancelled: () -> Boolean = { false },
    private val onBytesProgress: (Long) -> Unit = {}
) : ArchiveSink {
    private val dirCache = mutableMapOf<String, DocumentFile>("" to backupRootDir)
    private val _entries = mutableListOf<ArchiveEntryMeta>()
    override val recordedEntries: List<ArchiveEntryMeta> get() = _entries
    override var totalBytesWritten: Long = 0L
        private set
    override val issues = IssueLog()
    private val written = hashSetOf<String>()
    override fun hasEntry(path: String): Boolean = path.trimStart('/') in written

    private var consecutiveFailures = 0

    override fun writeEntry(
        path: String,
        category: ExportCategory,
        compress: Boolean,
        writer: (OutputStream) -> Unit
    ) {
        if (isCancelled()) throw CancellationException("Export cancelled")
        val normalizedPath = path.trimStart('/')
        if (hasEntry(normalizedPath)) return
        val parentPath = normalizedPath.substringBeforeLast('/', "")
        val fileName = normalizedPath.substringAfterLast('/')
        var created: DocumentFile? = null
        val hashing: HashingOutputStream
        try {
            val parentDir = ensureDirectory(parentPath)
            parentDir.findFile(fileName)?.delete()
            created = parentDir.createFile("application/octet-stream", fileName)
                ?: throw IOException("Failed to create USB file: $normalizedPath")

            val rawOut = context.contentResolver.openOutputStream(created.uri)
                ?: throw IOException("Failed to open USB output stream: $normalizedPath")

            val plainWrapperOut = if (!encryptionPassword.isNullOrBlank()) {
                EtgChunkedCipherOutputStream(BufferedOutputStream(rawOut, 32 * 1024), encryptionPassword)
            } else {
                BufferedOutputStream(rawOut, 32 * 1024)
            }

            hashing = HashingOutputStream(DestinationGuard(plainWrapperOut))
            hashing.use { out ->
                writer(out)
            }
            consecutiveFailures = 0
        } catch (t: Throwable) {
            if (IssueLog.isFatal(t)) throw t
            runCatching { created?.delete() }

            val destinationFailed = t is DestinationWriteException || created == null ||
                (t is IOException && t.message?.startsWith("Failed to") == true)
            if (destinationFailed && ++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throw SinkBrokenException(t)
            }
            throw (t as? DestinationWriteException)?.cause ?: t
        }

        val size = hashing.bytesWritten
        totalBytesWritten += size
        written += normalizedPath
        _entries += ArchiveEntryMeta(
            path = normalizedPath,
            category = category,
            sizeBytes = size,
            sha256 = hashing.sha256Hex()
        )
        onBytesProgress(totalBytesWritten)
    }

    private fun ensureDirectory(relativeDir: String): DocumentFile {
        if (relativeDir.isBlank()) return backupRootDir
        dirCache[relativeDir]?.let { return it }
        val parts = relativeDir.split('/').filter { it.isNotBlank() }
        var current = backupRootDir
        val acc = StringBuilder()
        for (part in parts) {
            if (acc.isNotEmpty()) acc.append('/')
            acc.append(part)
            val key = acc.toString()
            current = dirCache[key] ?: run {
                val existing = current.findFile(part)?.takeIf { it.isDirectory }
                val dir = existing ?: current.createDirectory(part)
                    ?: throw IllegalStateException("Cannot create USB directory: $key")
                dirCache[key] = dir
                dir
            }
        }
        return current
    }

    override fun close() = Unit

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 12
    }

    private class DestinationWriteException(cause: IOException) : IOException(cause.message, cause)

    private class DestinationGuard(private val out: OutputStream) : OutputStream() {
        private inline fun <T> guard(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw DestinationWriteException(e)
        }
        override fun write(b: Int) = guard { out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }
        override fun flush() = guard { out.flush() }
        override fun close() = guard { out.close() }
    }
}

object ExportOrchestrator {
    fun runExport(
        context: Context,
        config: ExportConfig,
        sink: ArchiveSink,
        estimatedTotalBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (OperationProgress) -> Unit,

        resolveIssues: (IssueLog) -> Unit = {}
    ): ArchiveManifest {
        val startMs = System.currentTimeMillis()
        val mediaIndex = mutableListOf<MediaFileEntry>()
        val summaryCounts = linkedMapOf<String, Int>()
        var auditNotes: List<GrapheneReadinessItem> = emptyList()

        fun emitProgress(
            cat: ExportCategory,
            label: String,
            processedItems: Int,
            totalItems: Int
        ) {
            val written = sink.totalBytesWritten
            val elapsedSec = maxOf(1L, (System.currentTimeMillis() - startMs) / 1000L)
            val speed = written / elapsedSec
            val eta = if (speed > 0L && estimatedTotalBytes > written) {
                (estimatedTotalBytes - written) / speed
            } else -1L
            onProgress(
                OperationProgress(
                    phase = OperationPhase.EXPORTING,
                    currentCategory = cat,
                    currentItemLabel = label,
                    processedItems = processedItems,
                    totalItems = totalItems,
                    processedBytes = written,
                    totalBytes = maxOf(estimatedTotalBytes, written),
                    speedBytesPerSec = speed,
                    etaSeconds = eta
                )
            )
        }

        val ordered = ExportCategory.entries.filter { it in config.selectedCategories }
        var installedAppsCache: List<org.etg.migration.model.AppPackageEntry>? = null

        fun runCategory(category: ExportCategory) {
            when (category) {
                ExportCategory.CONTACTS -> {
                    val count = ContactsExporter.export(context, sink, config.excluded(category)) { p, t, lbl ->
                        emitProgress(category, lbl, p, t)
                    }
                    summaryCounts[category.name] = count
                }
                ExportCategory.MESSAGES_SMS_MMS -> {
                    val count = MessagesAndCallsExporter.exportSmsMms(context, sink, config.excluded(category)) { p, t, lbl ->
                        emitProgress(category, lbl, p, t)
                    }
                    summaryCounts[category.name] = count
                    val blocked = MessagesAndCallsExporter.exportBlockedNumbers(context, sink)
                    if (blocked > 0) summaryCounts["BLOCKED_NUMBERS"] = blocked
                }
                ExportCategory.CALL_LOG -> {
                    val count = MessagesAndCallsExporter.exportCallLog(context, sink) { p, t, lbl ->
                        emitProgress(category, lbl, p, t)
                    }
                    summaryCounts[category.name] = count
                }
                ExportCategory.CHAT_BACKUPS -> {
                    val count = MediaAndFilesExporter.exportChatBackups(
                        context, config.extraSafFolders, sink, mediaIndex
                    ) { p, t, lbl -> emitProgress(category, lbl, p, t) } +
                        MediaAndFilesExporter.exportPickedFiles(
                            context, config.pickedFiles, category, sink, mediaIndex
                        ) { p, t, lbl -> emitProgress(category, lbl, p, t) }
                    summaryCounts[category.name] = count
                }
                ExportCategory.CALENDAR -> {
                    val count = CalendarExporter.export(context, sink, config.excluded(category)) { p, t, lbl ->
                        emitProgress(category, lbl, p, t)
                    }
                    summaryCounts[category.name] = count
                }
                ExportCategory.PHOTOS_VIDEOS -> {
                    val count = MediaAndFilesExporter.exportPhotosVideos(
                        context, config.includeVideos, config.excluded(category), sink, mediaIndex
                    ) { p, t, lbl -> emitProgress(category, lbl, p, t) }
                    summaryCounts[category.name] = count
                }
                ExportCategory.AUDIO_RINGTONES -> {
                    val count = MediaAndFilesExporter.exportAudio(
                        context, config.excluded(category), sink, mediaIndex
                    ) { p, t, lbl -> emitProgress(category, lbl, p, t) }
                    summaryCounts[category.name] = count
                }
                ExportCategory.DOCUMENTS_DOWNLOADS -> {
                    val count = MediaAndFilesExporter.exportDocumentsDownloads(
                        context, config.extraSafFolders, config.excluded(category), sink, mediaIndex
                    ) { p, t, lbl -> emitProgress(category, lbl, p, t) }
                    summaryCounts[category.name] = count
                }
                ExportCategory.VAULT_KEYS_2FA -> {
                    val count = MediaAndFilesExporter.exportVaultKeys(
                        context, config.extraSafFolders, sink, mediaIndex
                    ) { p, t, lbl -> emitProgress(category, lbl, p, t) }
                    val otp = AuthenticatorExporter.export(config.authenticatorAccounts, sink, mediaIndex)
                    val picked = MediaAndFilesExporter.exportPickedFiles(
                        context, config.pickedFiles, category, sink, mediaIndex
                    ) { p, t, lbl -> emitProgress(category, lbl, p, t) }
                    summaryCounts[category.name] = count + otp + picked
                }
                ExportCategory.WIFI_NETWORKS -> {
                    val count = WifiExporter.export(config.manualWifiEntries, sink)
                    summaryCounts[category.name] = count
                }
                ExportCategory.APPS_AND_APKS -> {
                    val (_, apps) = AppsExporter.scan(context, config.includeApks)
                    installedAppsCache = apps
                    val count = AppsExporter.export(context, config.includeApks, sink, config.excluded(category)) { p, t, lbl ->
                        emitProgress(category, lbl, p, t)
                    }
                    summaryCounts[category.name] = count
                }
                ExportCategory.SYSTEM_SETTINGS -> {
                    val apps = installedAppsCache ?: AppsExporter.scan(context, false).second
                    auditNotes = SettingsExporter.export(context, apps, sink, config.excluded(category))
                    summaryCounts[category.name] = 1
                }
            }
        }

        for (category in ordered) {
            if (isCancelled()) throw CancellationException("Export cancelled")
            emitProgress(category, category.name, 0, 1)

            attempt { runCategory(category) }.onFailure { t ->
                val (reason, detail) = IssueLog.reasonOf(t)
                sink.issues.report(
                    TransferIssue(
                        phase = IssuePhase.EXPORT,
                        category = category,
                        itemName = category.name,
                        reason = reason,
                        reasonDetail = detail
                    )
                ) {
                    attempt { runCategory(category) }.fold({ null }, { IssueLog.reasonOf(it) })
                }
            }
        }

        if (!sink.issues.isEmpty) resolveIssues(sink.issues)

        val manifest = ArchiveManifest(
            version = 1,
            createdAtIso = Instant.now().toString(),
            sourceDeviceModel = Build.MODEL,
            sourceManufacturer = Build.MANUFACTURER,
            sourceAndroidVersion = Build.VERSION.RELEASE,
            sourceSdkInt = Build.VERSION.SDK_INT,
            encrypted = config.encryptArchive && config.encryptionPassword.isNotBlank(),
            categories = ordered,
            entries = sink.recordedEntries.toList(),
            mediaIndex = mediaIndex.distinctBy { it.archivePath },
            grapheneAuditNotes = auditNotes,
            summaryCounts = summaryCounts,
            failedEntries = sink.failedEntries.toList(),
            entryAliases = sink.entryAliases.toMap(),
            exportIssues = sink.issues.all()
        )

        val manifestBytes = EtgJson.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("manifest.json", ExportCategory.SYSTEM_SETTINGS, manifestBytes, compress = true)
        return manifest
    }
}
