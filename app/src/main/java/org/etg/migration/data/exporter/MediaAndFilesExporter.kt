package org.etg.migration.data.exporter

import org.etg.migration.R
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import org.etg.migration.data.PermissionHelper
import org.etg.migration.data.archive.ArchiveSink
import org.etg.migration.data.archive.IssueLog
import org.etg.migration.data.archive.attempt
import org.etg.migration.model.IssuePhase
import org.etg.migration.model.IssueReason
import org.etg.migration.model.TransferIssue
import org.etg.migration.model.CategoryScanInfo
import org.etg.migration.model.CategoryStatus
import org.etg.migration.model.ChatAppInfo
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.MediaFileEntry
import java.io.File
import java.io.InputStream

object MediaAndFilesExporter {
    private data class MessengerDef(
        val packageName: String,
        val displayName: String,
        val candidateFolders: List<String>,
        val instructions: String
    )

    private val KNOWN_MESSENGERS = listOf(
        MessengerDef(
            packageName = "org.thoughtcrime.securesms",
            displayName = "Signal",
            candidateFolders = listOf("Signal/Backups", "Documents/Signal", "Download/Signal", "Backups/Signal"),
            instructions = "Signal -> Settings -> Chats -> Chat backups -> Turn on & copy 30-digit passphrase before wiping!"
        ),
        MessengerDef(
            packageName = "im.molly.app",
            displayName = "Molly (FOSS Signal fork)",
            candidateFolders = listOf("Molly/Backups", "Documents/Molly", "Download/Molly"),
            instructions = "Molly -> Settings -> Chats -> Backups. Save your 30-digit passphrase."
        ),
        MessengerDef(
            packageName = "com.whatsapp",
            displayName = "WhatsApp",
            candidateFolders = listOf(
                "Android/media/com.whatsapp/WhatsApp/Databases",
                "Android/media/com.whatsapp/WhatsApp/Backups",
                "Android/media/com.whatsapp/WhatsApp/Media",
                "WhatsApp/Databases",
                "WhatsApp/Backups",
                "WhatsApp/Media"
            ),
            instructions = "WhatsApp -> Settings -> Chats -> Chat backup -> Back up locally (disable Google Drive backup, keep 64-digit key or password!)."
        ),
        MessengerDef(
            packageName = "com.whatsapp.w4b",
            displayName = "WhatsApp Business",
            candidateFolders = listOf(
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Databases",
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Backups",
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"
            ),
            instructions = "WhatsApp Business -> Local backup + E2E backup key."
        ),
        MessengerDef(
            packageName = "org.telegram.messenger",
            displayName = "Telegram",
            candidateFolders = listOf("Android/media/org.telegram.messenger/Telegram", "Pictures/Telegram", "Download/Telegram"),
            instructions = "Cloud-synced automatically, local media/downloads exported if present."
        ),
        MessengerDef(
            packageName = "im.vector.app",
            displayName = "Element (Matrix)",
            candidateFolders = listOf("Download/Element", "Documents/Element"),
            instructions = "Element -> Settings -> Security & Privacy -> Export E2E room keys + save recovery key!"
        ),
        MessengerDef(
            packageName = "network.loki.messenger",
            displayName = "Session",
            candidateFolders = listOf("Documents/Session", "Download/Session"),
            instructions = "Session -> Settings -> Recovery Phrase (13 words)."
        ),
        MessengerDef(
            packageName = "pl.sinusoide.sinuschat",
            displayName = "SinusChat",
            candidateFolders = listOf("Documents/SinusChat", "Download/SinusChat", "SinusChat"),
            instructions = "SinusChat -> Settings -> Backup & transfer -> Back up to phone (Documents). Keep your 12 recovery words – the .sinuschat file cannot be opened without them."
        ),
        MessengerDef(
            packageName = "chat.simplex.app",
            displayName = "SimpleX Chat",
            candidateFolders = listOf("Documents/SimpleX", "Download/SimpleX"),
            instructions = "SimpleX -> Settings -> Database & Export -> Export encrypted database archive (.zip)!"
        )
    )

    private val PRECOMPRESSED_EXTS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "mp4", "mov", "mkv", "webm",
        "avi", "mp3", "m4a", "aac", "ogg", "opus", "flac", "zip", "gz", "7z", "rar",
        "apk", "kdbx", "crypt14", "crypt15", "backup", "pdf", "sinuschat"
    )

    fun scanPhotosVideos(context: Context, includeVideos: Boolean): CategoryScanInfo {
        val missing = mutableListOf<String>()
        if (!PermissionHelper.hasImagesRead(context)) {
            missing += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }
        if (includeVideos && !PermissionHelper.hasVideosRead(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                missing += Manifest.permission.READ_MEDIA_VIDEO
            }
        }
        if (missing.isNotEmpty()) {
            return CategoryScanInfo(
                category = ExportCategory.PHOTOS_VIDEOS,
                status = CategoryStatus.NEEDS_PERMISSION,
                missingPermissions = missing.distinct()
            )
        }
        val (imgCount, imgBytes) = queryMediaStats(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val (vidCount, vidBytes) = if (includeVideos) {
            queryMediaStats(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        } else {
            0 to 0L
        }
        val total = imgCount + vidCount
        return CategoryScanInfo(
            category = ExportCategory.PHOTOS_VIDEOS,
            itemCount = total,
            estimatedBytes = imgBytes + vidBytes,
            status = if (total > 0) CategoryStatus.READY else CategoryStatus.EMPTY,
            detailSummary = if (includeVideos) context.getString(R.string.scan_media, imgCount, vidCount) else context.getString(R.string.scan_media_img, imgCount)
        )
    }

    fun scanAudio(context: Context): CategoryScanInfo {
        if (!PermissionHelper.hasAudioRead(context)) {
            return CategoryScanInfo(
                category = ExportCategory.AUDIO_RINGTONES,
                status = CategoryStatus.NEEDS_PERMISSION,
                missingPermissions = listOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.READ_MEDIA_AUDIO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                )
            )
        }
        val (count, bytes) = queryMediaStats(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        return CategoryScanInfo(
            category = ExportCategory.AUDIO_RINGTONES,
            itemCount = count,
            estimatedBytes = bytes,
            status = if (count > 0) CategoryStatus.READY else CategoryStatus.EMPTY,
            detailSummary = "$count"
        )
    }

    fun scanDocumentsDownloads(context: Context, extraSafFolders: List<String>): CategoryScanInfo {
        val allFiles = PermissionHelper.hasAllFilesAccess(context)
        val files = collectDocumentsFiles(context, extraSafFolders)
        if (!allFiles && files.isEmpty()) {
            return CategoryScanInfo(
                category = ExportCategory.DOCUMENTS_DOWNLOADS,
                status = CategoryStatus.NEEDS_PERMISSION,
                requiresAllFilesAccess = true,
                detailSummary = "All Files / SAF"
            )
        }
        val totalBytes = files.sumOf { it.sizeBytes }
        return CategoryScanInfo(
            category = ExportCategory.DOCUMENTS_DOWNLOADS,
            itemCount = files.size,
            estimatedBytes = totalBytes,
            status = if (files.isNotEmpty()) CategoryStatus.READY else CategoryStatus.EMPTY,
            requiresAllFilesAccess = !allFiles,
            detailSummary = "${files.size}"
        )
    }

    fun scanVaultKeys(context: Context, extraSafFolders: List<String>): CategoryScanInfo {
        val allFiles = PermissionHelper.hasAllFilesAccess(context)
        val files = collectVaultFiles(context, extraSafFolders)
        if (!allFiles && files.isEmpty()) {
            return CategoryScanInfo(
                category = ExportCategory.VAULT_KEYS_2FA,
                status = CategoryStatus.GUIDED,
                requiresAllFilesAccess = true,
                detailSummary = "KeePass / 2FA / VPN / SSH"
            )
        }
        return CategoryScanInfo(
            category = ExportCategory.VAULT_KEYS_2FA,
            itemCount = files.size,
            estimatedBytes = files.sumOf { it.sizeBytes },
            status = if (files.isNotEmpty()) CategoryStatus.READY else CategoryStatus.GUIDED,
            requiresAllFilesAccess = !allFiles,
            detailSummary = if (files.isNotEmpty()) context.getString(R.string.scan_vault_files, files.size) else "KeePass / Aegis / VPN"
        )
    }

    fun inspectChatApps(context: Context, extraSafFolders: List<String>): Pair<CategoryScanInfo, List<ChatAppInfo>> {
        val pm = context.packageManager
        val root = Environment.getExternalStorageDirectory()
        val hasAllFiles = PermissionHelper.hasAllFilesAccess(context)

        val infos = KNOWN_MESSENGERS.map { def ->
            val installed = runCatching {
                pm.getPackageInfo(def.packageName, 0)
                true
            }.getOrDefault(false)

            var fileCount = 0
            var byteCount = 0L
            val foundPaths = mutableListOf<String>()

            if (hasAllFiles) {
                for (rel in def.candidateFolders) {
                    val dir = File(root, rel)
                    if (dir.exists() && dir.isDirectory) {
                        val (cnt, bytes) = countDirFiles(dir)
                        if (cnt > 0) {
                            foundPaths += rel
                            fileCount += cnt
                            byteCount += bytes
                        }
                    }
                }
            }
            ChatAppInfo(
                packageName = def.packageName,
                displayName = def.displayName,
                installed = installed,
                backupPaths = foundPaths,
                detectedFilesCount = fileCount,
                detectedBytes = byteCount,
                instructions = def.instructions
            )
        }

        val safFiles = collectChatSafFiles(context, extraSafFolders)
        val totalFiles = infos.sumOf { it.detectedFilesCount } + safFiles.size
        val totalBytes = infos.sumOf { it.detectedBytes } + safFiles.sumOf { it.sizeBytes }
        val installedCount = infos.count { it.installed }

        val status = when {
            totalFiles > 0 -> CategoryStatus.READY
            !hasAllFiles && installedCount > 0 -> CategoryStatus.NEEDS_PERMISSION
            else -> CategoryStatus.GUIDED
        }

        val scanInfo = CategoryScanInfo(
            category = ExportCategory.CHAT_BACKUPS,
            itemCount = totalFiles,
            estimatedBytes = totalBytes,
            status = status,
            requiresAllFilesAccess = !hasAllFiles,
            detailSummary = context.getString(R.string.scan_chats, installedCount, totalFiles)
        )
        return scanInfo to infos
    }

    fun exportPhotosVideos(
        context: Context,
        includeVideos: Boolean,
        excludedFolders: Set<String> = emptySet(),
        sink: ArchiveSink,
        mediaIndexOut: MutableList<MediaFileEntry>,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        val items = mutableListOf<PendingMediaQuery>()
        if (PermissionHelper.hasImagesRead(context)) {
            items += queryMediaItems(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photos")
        }
        if (includeVideos && PermissionHelper.hasVideosRead(context)) {
            items += queryMediaItems(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "videos")
        }
        if (excludedFolders.isNotEmpty()) {
            items.removeAll { SelectionCatalog.mediaFolderKey(it.relativePath) in excludedFolders }
        }
        return exportMediaItems(context, items, ExportCategory.PHOTOS_VIDEOS, sink, mediaIndexOut, onItemProgress)
    }

    fun listAudio(context: Context): List<SelectableItem> {
        if (!PermissionHelper.hasAudioRead(context)) return emptyList()
        return queryMediaItems(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio")
            .sortedWith(compareBy({ it.relativePath.lowercase() }, { it.displayName.lowercase() }))
            .map { SelectableItem(it.contentUri.toString(), it.displayName, it.relativePath, bytes = it.sizeBytes) }
    }

    fun listDocuments(context: Context, extraSafFolders: List<String>): List<SelectableItem> =
        collectDocumentsFiles(context, extraSafFolders)
            .sortedWith(compareBy({ it.relativePath.lowercase() }, { it.fileName.lowercase() }))
            .map { SelectableItem(documentKey(it), it.fileName, it.relativePath, bytes = it.sizeBytes) }

    private fun documentKey(f: PendingDiskFile) = "${f.relativePath.trim('/')}/${f.fileName}"

    fun exportAudio(
        context: Context,
        excluded: Set<String> = emptySet(),
        sink: ArchiveSink,
        mediaIndexOut: MutableList<MediaFileEntry>,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        if (!PermissionHelper.hasAudioRead(context)) return 0
        val items = queryMediaItems(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio")
            .filter { it.contentUri.toString() !in excluded }
        return exportMediaItems(context, items, ExportCategory.AUDIO_RINGTONES, sink, mediaIndexOut, onItemProgress)
    }

    fun exportDocumentsDownloads(
        context: Context,
        extraSafFolders: List<String>,
        excluded: Set<String> = emptySet(),
        sink: ArchiveSink,
        mediaIndexOut: MutableList<MediaFileEntry>,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        val items = collectDocumentsFiles(context, extraSafFolders).filter { documentKey(it) !in excluded }
        return exportDiskOrSafItems(context, items, ExportCategory.DOCUMENTS_DOWNLOADS, "documents", sink, mediaIndexOut, onItemProgress)
    }

    fun exportVaultKeys(
        context: Context,
        extraSafFolders: List<String>,
        sink: ArchiveSink,
        mediaIndexOut: MutableList<MediaFileEntry>,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        val items = collectVaultFiles(context, extraSafFolders)
        return exportDiskOrSafItems(context, items, ExportCategory.VAULT_KEYS_2FA, "vault", sink, mediaIndexOut, onItemProgress)
    }

    fun exportPickedFiles(
        context: Context,
        files: List<org.etg.migration.model.PickedFile>,
        category: ExportCategory,
        sink: ArchiveSink,
        mediaIndexOut: MutableList<MediaFileEntry>,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        val baseDir = if (category == ExportCategory.CHAT_BACKUPS) "Documents/ETG_Chats" else "Documents/ETG_Vault"
        val items = files.filter { it.category == category }.map { f ->
            PendingDiskFile(
                file = null,
                docUri = Uri.parse(f.uri),

                relativePath = when {
                    f.name.endsWith(".sinuschat", ignoreCase = true) -> "Documents/SinusChat"
                    f.label.isNotBlank() -> "$baseDir/${f.label}"
                    else -> baseDir
                },
                fileName = f.name,
                mimeType = guessMime(f.name),
                sizeBytes = f.sizeBytes,
                dateModifiedSec = System.currentTimeMillis() / 1000L
            )
        }
        if (items.isEmpty()) return 0
        val prefix = if (category == ExportCategory.CHAT_BACKUPS) "chats/picked" else "vault/picked"
        return exportDiskOrSafItems(context, items, category, prefix, sink, mediaIndexOut, onItemProgress)
    }

    fun exportChatBackups(
        context: Context,
        extraSafFolders: List<String>,
        sink: ArchiveSink,
        mediaIndexOut: MutableList<MediaFileEntry>,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        val items = mutableListOf<PendingDiskFile>()
        if (PermissionHelper.hasAllFilesAccess(context)) {
            val root = Environment.getExternalStorageDirectory()
            val visitedPaths = hashSetOf<String>()
            for (def in KNOWN_MESSENGERS) {
                for (rel in def.candidateFolders) {
                    val dir = File(root, rel)
                    if (dir.exists() && dir.isDirectory) {
                        collectFilesRecursively(dir, root, items, visitedPaths)
                    }
                }
            }
        }
        items += collectChatSafFiles(context, extraSafFolders)
        return exportDiskOrSafItems(context, items, ExportCategory.CHAT_BACKUPS, "chats", sink, mediaIndexOut, onItemProgress)
    }

    private data class PendingMediaQuery(
        val contentUri: Uri,
        val subBucket: String,
        val relativePath: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val dateModifiedSec: Long,
        val dateTakenMs: Long
    )

    private data class PendingDiskFile(
        val file: File?,
        val docUri: Uri?,
        val relativePath: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val dateModifiedSec: Long
    )

    private fun queryMediaStats(context: Context, uri: Uri): Pair<Int, Long> {
        var count = 0
        var totalSize = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.SIZE),
                null,
                null,
                null
            )?.use { c ->
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                count = c.count
                while (c.moveToNext()) {
                    totalSize += c.getLong(sizeIdx).coerceAtLeast(0L)
                }
            }
        }
        return count to totalSize
    }

    private fun queryMediaItems(context: Context, uri: Uri, subBucket: String): List<PendingMediaQuery> {
        val result = mutableListOf<PendingMediaQuery>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.MediaColumns.RELATIVE_PATH
            projection += MediaStore.MediaColumns.DATE_TAKEN
        } else {
            @Suppress("DEPRECATION")
            projection += MediaStore.MediaColumns.DATA
        }

        runCatching {
            context.contentResolver.query(uri, projection.toTypedArray(), null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else -1
                val takenIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                } else -1
                @Suppress("DEPRECATION")
                val dataIdx = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.MediaColumns.DATA)
                } else -1

                val storageRoot = Environment.getExternalStorageDirectory().absolutePath
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val displayName = c.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: "media_$id"
                    val mime = c.getString(mimeIdx) ?: "application/octet-stream"
                    val size = c.getLong(sizeIdx).coerceAtLeast(0L)
                    val mod = c.getLong(modIdx).coerceAtLeast(0L)
                    val taken = if (takenIdx >= 0) c.getLong(takenIdx) else mod * 1000L
                    val relPath = if (relIdx >= 0) {
                        c.getString(relIdx).orEmpty()
                    } else if (dataIdx >= 0) {
                        val abs = c.getString(dataIdx).orEmpty()
                        if (abs.startsWith(storageRoot)) {
                            abs.removePrefix(storageRoot).trimStart('/').substringBeforeLast('/', "")
                        } else ""
                    } else ""
                    val normalizedRel = relPath.trim('/').ifBlank {
                        when (subBucket) {
                            "photos" -> "Pictures/ETG"
                            "videos" -> "Movies/ETG"
                            else -> "Music/ETG"
                        }
                    }
                    result += PendingMediaQuery(
                        contentUri = ContentUris.withAppendedId(uri, id),
                        subBucket = subBucket,
                        relativePath = normalizedRel,
                        displayName = displayName,
                        mimeType = mime,
                        sizeBytes = size,
                        dateModifiedSec = mod,
                        dateTakenMs = taken
                    )
                }
            }
        }
        return result
    }

    private fun exportMediaItems(
        context: Context,
        items: List<PendingMediaQuery>,
        category: ExportCategory,
        sink: ArchiveSink,
        mediaIndexOut: MutableList<MediaFileEntry>,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        val total = items.size
        var exported = 0
        val usedPaths = hashSetOf<String>()
        val cr = context.contentResolver

        for ((idx, item) in items.withIndex()) {
            onItemProgress(idx + 1, total, item.displayName)
            var archivePath = "media/${item.subBucket}/${item.relativePath}/${item.displayName}"
                .replace("//", "/")
            if (!usedPaths.add(archivePath)) {
                archivePath = "media/${item.subBucket}/${item.relativePath}/${idx}_${item.displayName}"
                    .replace("//", "/")
                usedPaths.add(archivePath)
            }
            val shouldCompress = !isPrecompressed(item.displayName)
            val path = archivePath
            fun copyOne(): Pair<IssueReason, String>? {
                val r = attempt {
                    cr.openInputStream(item.contentUri)?.use { input ->
                        sink.copyStream(path, category, input, compress = shouldCompress)
                        true
                    } ?: false
                }
                if (r.getOrDefault(false)) {
                    exported++
                    mediaIndexOut += MediaFileEntry(
                        category = category,
                        archivePath = path,
                        relativePath = item.relativePath,
                        fileName = item.displayName,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes,
                        dateModifiedSec = item.dateModifiedSec,
                        dateTakenMs = item.dateTakenMs
                    )
                    return null
                }
                return r.exceptionOrNull()?.let { IssueLog.reasonOf(it) } ?: (IssueReason.NOT_FOUND to "")
            }
            copyOne()?.let { (reason, detail) ->
                sink.issues.report(
                    TransferIssue(
                        phase = IssuePhase.EXPORT,
                        category = category,
                        itemName = item.displayName,
                        detail = item.relativePath,
                        reason = reason,
                        reasonDetail = detail,
                        categoryTotal = total
                    )
                ) { copyOne() }
            }
        }
        return exported
    }

    private fun collectDocumentsFiles(context: Context, extraSafFolders: List<String>): List<PendingDiskFile> {
        val items = mutableListOf<PendingDiskFile>()
        val visited = hashSetOf<String>()
        if (PermissionHelper.hasAllFilesAccess(context)) {
            val root = Environment.getExternalStorageDirectory()
            listOf("Documents", "Download").forEach { folder ->
                val dir = File(root, folder)
                if (dir.exists() && dir.isDirectory) {
                    collectFilesRecursively(dir, root, items, visited)
                }
            }
        }
        for (uriStr in extraSafFolders) {
            val docDir = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) }.getOrNull()
            if (docDir != null && docDir.exists()) {
                collectSafRecursively(docDir, "Documents/ETG_Custom/${docDir.name ?: "Folder"}", items)
            }
        }
        return items
    }

    private fun collectVaultFiles(context: Context, extraSafFolders: List<String>): List<PendingDiskFile> {
        val candidates = mutableListOf<PendingDiskFile>()
        val visited = hashSetOf<String>()
        if (PermissionHelper.hasAllFilesAccess(context)) {
            val root = Environment.getExternalStorageDirectory()
            val dirs = listOf(
                File(root, "Documents"),
                File(root, "Download"),
                File(root, ".ssh"),
                File(root, "Aegis"),
                File(root, "KeePass"),
                File(root, "WireGuard"),
                File(root, "OpenVPN")
            )
            val tmp = mutableListOf<PendingDiskFile>()
            dirs.forEach { d ->
                if (d.exists() && d.isDirectory) {
                    collectFilesRecursively(d, root, tmp, visited)
                }
            }
            candidates += tmp.filter { isVaultOrKeyFile(it.fileName, it.relativePath) }
        }
        for (uriStr in extraSafFolders) {
            val docDir = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) }.getOrNull()
            if (docDir != null && docDir.exists()) {
                val tmp = mutableListOf<PendingDiskFile>()
                collectSafRecursively(docDir, "Documents/ETG_Vault/${docDir.name ?: "Vault"}", tmp)
                candidates += tmp.filter { isVaultOrKeyFile(it.fileName, it.relativePath) }
            }
        }
        return candidates
    }

    private fun collectChatSafFiles(context: Context, extraSafFolders: List<String>): List<PendingDiskFile> {
        val out = mutableListOf<PendingDiskFile>()
        for (uriStr in extraSafFolders) {
            val docDir = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) }.getOrNull() ?: continue
            val nameLower = (docDir.name ?: "").lowercase()
            if (nameLower.contains("signal") || nameLower.contains("whatsapp") ||
                nameLower.contains("molly") || nameLower.contains("simplex") ||
                nameLower.contains("telegram") || nameLower.contains("backup") ||
                nameLower.contains("sinuschat")
            ) {
                collectSafRecursively(docDir, "Download/ETG_Chats/${docDir.name ?: "Chats"}", out)
            }
        }
        return out
    }

    private fun isVaultOrKeyFile(fileName: String, relPath: String): Boolean {
        val lower = fileName.lowercase()
        val pathLower = relPath.lowercase()
        val ext = lower.substringAfterLast('.', "")
        if (ext in setOf("kdbx", "ovpn", "asc", "pgp", "gpg", "pem", "p12", "pfx", "2fas")) return true
        if (ext == "conf" && (lower.startsWith("wg") || pathLower.contains("wireguard") || pathLower.contains("vpn"))) return true
        if (lower.startsWith("id_ed25519") || lower.startsWith("id_rsa") || lower.startsWith("id_ecdsa")) return true
        if (lower.contains("aegis") || lower.contains("2fas") || lower.contains("freeotp") ||
            lower.contains("ente_auth") || lower.contains("keepass") || lower.contains("bitwarden")
        ) return true
        if (lower.contains("bookmark") && ext == "html") return true
        return false
    }

    private fun exportDiskOrSafItems(
        context: Context,
        items: List<PendingDiskFile>,
        category: ExportCategory,
        archivePrefix: String,
        sink: ArchiveSink,
        mediaIndexOut: MutableList<MediaFileEntry>,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        val total = items.size
        var exported = 0
        val usedPaths = hashSetOf<String>()
        val cr = context.contentResolver

        for ((idx, item) in items.withIndex()) {
            onItemProgress(idx + 1, total, item.fileName)
            var archivePath = "$archivePrefix/${item.relativePath}/${item.fileName}".replace("//", "/")
            if (!usedPaths.add(archivePath)) {
                archivePath = "$archivePrefix/${item.relativePath}/${idx}_${item.fileName}".replace("//", "/")
                usedPaths.add(archivePath)
            }
            val shouldCompress = !isPrecompressed(item.fileName)
            val path = archivePath
            fun copyOne(): Pair<IssueReason, String>? {
                val r = attempt {
                    when {
                        item.file != null -> {
                            item.file.inputStream().use { input ->
                                sink.copyStream(path, category, input, compress = shouldCompress)
                            }
                            true
                        }
                        item.docUri != null -> {
                            cr.openInputStream(item.docUri)?.use { input ->
                                sink.copyStream(path, category, input, compress = shouldCompress)
                                true
                            } ?: false
                        }
                        else -> false
                    }
                }
                if (r.getOrDefault(false)) {
                    exported++
                    mediaIndexOut += MediaFileEntry(
                        category = category,
                        archivePath = path,
                        relativePath = item.relativePath,
                        fileName = item.fileName,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes,
                        dateModifiedSec = item.dateModifiedSec
                    )
                    return null
                }
                return r.exceptionOrNull()?.let { IssueLog.reasonOf(it) } ?: (IssueReason.NOT_FOUND to "")
            }
            copyOne()?.let { (reason, detail) ->
                sink.issues.report(
                    TransferIssue(
                        phase = IssuePhase.EXPORT,
                        category = category,
                        itemName = item.fileName,
                        detail = item.relativePath,
                        reason = reason,
                        reasonDetail = detail,
                        categoryTotal = total
                    )
                ) { copyOne() }
            }
        }
        return exported
    }

    fun restoreMediaOrFileEntry(
        context: Context,
        entry: MediaFileEntry,
        inputStream: InputStream
    ): Boolean {
        val hasAllFiles = PermissionHelper.hasAllFilesAccess(context)
        return when (entry.category) {
            ExportCategory.PHOTOS_VIDEOS -> restoreViaMediaStore(
                context = context,
                entry = entry,
                inputStream = inputStream,
                isVideo = entry.mimeType.startsWith("video/") || entry.archivePath.contains("/videos/"),
                isAudio = false
            )
            ExportCategory.AUDIO_RINGTONES -> restoreViaMediaStore(
                context = context,
                entry = entry,
                inputStream = inputStream,
                isVideo = false,
                isAudio = true
            )
            ExportCategory.DOCUMENTS_DOWNLOADS,
            ExportCategory.VAULT_KEYS_2FA,
            ExportCategory.CHAT_BACKUPS -> restoreFileDirectlyOrMediaStore(
                context = context,
                entry = entry,
                inputStream = inputStream,
                hasAllFiles = hasAllFiles
            )
            else -> false
        }
    }

    private fun restoreViaMediaStore(
        context: Context,
        entry: MediaFileEntry,
        inputStream: InputStream,
        isVideo: Boolean,
        isAudio: Boolean
    ): Boolean {
        val cr = context.contentResolver
        val collection = when {
            isVideo -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            isAudio -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val cleanRel = entry.relativePath.trim('/').let { if (it.isEmpty()) "ETG_Restored/" else "$it/" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val dupCheck = runCatching {
                cr.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                    arrayOf(entry.fileName, cleanRel, entry.sizeBytes.toString()),
                    null
                )?.use { it.count > 0 } ?: false
            }.getOrDefault(false)
            if (dupCheck) return true
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType.ifBlank { "application/octet-stream" })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, cleanRel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            if (isAudio) {
                val lowerRel = cleanRel.lowercase()
                put(MediaStore.Audio.Media.IS_RINGTONE, lowerRel.contains("ringtone"))
                put(MediaStore.Audio.Media.IS_ALARM, lowerRel.contains("alarm"))
                put(MediaStore.Audio.Media.IS_NOTIFICATION, lowerRel.contains("notification"))
                put(
                    MediaStore.Audio.Media.IS_MUSIC,
                    !lowerRel.contains("ringtone") && !lowerRel.contains("alarm") && !lowerRel.contains("notification")
                )
            }
        }

        val uri = cr.insert(collection, values) ?: return false
        return runCatching {
            cr.openOutputStream(uri)?.use { out ->
                inputStream.copyTo(out, 64 * 1024)
            } ?: throw IllegalStateException("Cannot open output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                cr.update(uri, doneValues, null, null)
            }
            true
        }.getOrElse {
            runCatching { cr.delete(uri, null, null) }
            false
        }
    }

    private fun restoreFileDirectlyOrMediaStore(
        context: Context,
        entry: MediaFileEntry,
        inputStream: InputStream,
        hasAllFiles: Boolean
    ): Boolean {
        val relDir = entry.relativePath.trim('/')
        if (hasAllFiles) {
            val root = Environment.getExternalStorageDirectory()
            val targetDir = if (relDir.isEmpty()) File(root, "Download/ETG_Restored") else File(root, relDir)
            if (!targetDir.exists()) targetDir.mkdirs()
            val outFile = File(targetDir, entry.fileName)
            if (outFile.exists() && outFile.length() == entry.sizeBytes && entry.sizeBytes > 0L) {
                return true
            }
            return runCatching {
                outFile.outputStream().use { out -> inputStream.copyTo(out, 64 * 1024) }
                true
            }.getOrDefault(false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = context.contentResolver
            val subFolder = when (entry.category) {
                ExportCategory.VAULT_KEYS_2FA -> "Download/ETG_Vault/"
                ExportCategory.CHAT_BACKUPS -> "Download/ETG_Chat_Backups/${relDir.ifEmpty { "Backups" }}/"
                else -> if (relDir.startsWith("Documents") || relDir.startsWith("Download")) "$relDir/" else "Download/ETG_Docs/$relDir/"
            }.replace("//", "/")

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, subFolder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = cr.insert(collection, values) ?: return false
            return runCatching {
                cr.openOutputStream(uri)?.use { out -> inputStream.copyTo(out, 64 * 1024) }
                    ?: throw IllegalStateException("Cannot open download output stream")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                cr.update(uri, done, null, null)
                true
            }.getOrElse {
                runCatching { cr.delete(uri, null, null) }
                false
            }
        }
        return false
    }

    private fun countDirFiles(dir: File): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name != "." && child.name != ".." && child.name != ".thumbnails") {
                        stack.addLast(child)
                    }
                } else if (child.isFile) {
                    count++
                    bytes += child.length()
                }
            }
        }
        return count to bytes
    }

    private fun collectFilesRecursively(
        dir: File,
        storageRoot: File,
        out: MutableList<PendingDiskFile>,
        visitedCanonical: MutableSet<String>
    ) {
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        val rootPath = storageRoot.absolutePath.trimEnd('/')
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val canonical = runCatching { current.canonicalPath }.getOrDefault(current.absolutePath)
            if (!visitedCanonical.add(canonical)) continue
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name != ".thumbnails" && child.name != ".Trash") {
                        stack.addLast(child)
                    }
                } else if (child.isFile) {
                    val parentAbs = child.parentFile?.absolutePath.orEmpty()
                    val relPath = if (parentAbs.startsWith(rootPath)) {
                        parentAbs.removePrefix(rootPath).trim('/')
                    } else "Download"
                    out += PendingDiskFile(
                        file = child,
                        docUri = null,
                        relativePath = relPath,
                        fileName = child.name,
                        mimeType = guessMime(child.name),
                        sizeBytes = child.length(),
                        dateModifiedSec = child.lastModified() / 1000L
                    )
                }
            }
        }
    }

    private fun collectSafRecursively(
        dir: DocumentFile,
        relPath: String,
        out: MutableList<PendingDiskFile>
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                collectSafRecursively(child, "$relPath/$name", out)
            } else if (child.isFile) {
                out += PendingDiskFile(
                    file = null,
                    docUri = child.uri,
                    relativePath = relPath,
                    fileName = name,
                    mimeType = child.type ?: guessMime(name),
                    sizeBytes = child.length(),
                    dateModifiedSec = child.lastModified() / 1000L
                )
            }
        }
    }

    private fun isPrecompressed(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in PRECOMPRESSED_EXTS
    }

    private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "txt", "log", "conf", "ovpn" -> "text/plain"
        "html" -> "text/html"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
