package org.etg.migration.data.archive

import org.etg.migration.data.integration.SinusChatBridge
import java.util.zip.ZipException
import org.etg.migration.model.IssuePhase
import org.etg.migration.model.IssueReason
import org.etg.migration.model.TransferIssue
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.etg.migration.crypto.EtgChunkedCipherInputStream
import org.etg.migration.crypto.EtgCryptoFormat
import org.etg.migration.crypto.Sha256Helper
import org.etg.migration.data.exporter.CalendarExporter
import org.etg.migration.data.exporter.ContactsExporter
import org.etg.migration.data.exporter.MediaAndFilesExporter
import org.etg.migration.data.exporter.MessagesAndCallsExporter
import org.etg.migration.data.exporter.SettingsExporter
import org.etg.migration.data.exporter.WifiExporter
import org.etg.migration.model.AppPackageEntry
import org.etg.migration.model.ArchiveManifest
import org.etg.migration.model.CategoryImportStat
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.ImportPreview
import org.etg.migration.model.ImportResult
import org.etg.migration.model.OperationPhase
import org.etg.migration.model.OperationProgress
import org.etg.migration.model.SystemSettingsSnapshot
import org.etg.migration.model.WifiNetworkEntry
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.AEADBadTagException

sealed class ArchiveSource {
    data class SafUri(val uri: Uri, val displayName: String) : ArchiveSource()
    data class LocalFile(val file: File) : ArchiveSource()
    data class UsbFolder(val treeUri: Uri, val displayName: String) : ArchiveSource()
}

object EtgArchiveReader {
    fun inspect(
        context: Context,
        source: ArchiveSource,
        password: String?
    ): ImportPreview {
        val label = when (source) {
            is ArchiveSource.SafUri -> source.displayName
            is ArchiveSource.LocalFile -> source.file.name
            is ArchiveSource.UsbFolder -> source.displayName
        }

        if (source is ArchiveSource.UsbFolder) {
            return inspectUsbFolder(context, source.treeUri, label, password)
        }

        val encrypted = detectEncryptedArchive(context, source)
        if (encrypted && password.isNullOrBlank()) {
            return ImportPreview(
                sourceLabel = label,
                isEncrypted = true,
                isUnlocked = false
            )
        }

        return runCatching {
            openPlainZipStream(context, source, password).use { zip ->
                var manifest: ArchiveManifest? = null
                var wifiList: List<WifiNetworkEntry> = emptyList()
                var appsList: List<AppPackageEntry> = emptyList()
                var settingsSnap: SystemSettingsSnapshot? = null
                var vcfFallbackPresent = false
                var entriesCount = 0
                var totalUncompressed = 0L

                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    entriesCount++
                    if (entry.size > 0L) totalUncompressed += entry.size
                    val name = entry.name.trimStart('/')
                    when (name) {
                        "manifest.json" -> {
                            val bytes = zip.readBytes()
                            manifest = EtgJson.decodeFromString<ArchiveManifest>(String(bytes, StandardCharsets.UTF_8))
                        }
                        "wifi/wifi_networks.json" -> {
                            wifiList = WifiExporter.decodeNetworks(zip.readBytes())
                        }
                        "apps/apps_manifest.json" -> {
                            appsList = runCatching {
                                EtgJson.decodeFromString<List<AppPackageEntry>>(String(zip.readBytes(), StandardCharsets.UTF_8))
                            }.getOrDefault(emptyList())
                        }
                        "settings/system_settings.json" -> {
                            settingsSnap = runCatching {
                                EtgJson.decodeFromString<SystemSettingsSnapshot>(String(zip.readBytes(), StandardCharsets.UTF_8))
                            }.getOrNull()
                        }
                        "contacts/contacts.vcf" -> vcfFallbackPresent = true
                    }
                    zip.closeEntry()
                }

                val inferredCategories = manifest?.categories?.toSet() ?: buildSet {
                    if (vcfFallbackPresent) add(ExportCategory.CONTACTS)
                    if (wifiList.isNotEmpty()) add(ExportCategory.WIFI_NETWORKS)
                    if (appsList.isNotEmpty()) add(ExportCategory.APPS_AND_APKS)
                    if (settingsSnap != null) add(ExportCategory.SYSTEM_SETTINGS)
                }

                ImportPreview(
                    sourceLabel = label,
                    isEncrypted = encrypted,
                    isUnlocked = true,
                    manifest = manifest,
                    archiveEntriesCount = manifest?.entries?.size ?: entriesCount,
                    totalUncompressedBytes = manifest?.entries?.sumOf { it.sizeBytes } ?: totalUncompressed,
                    wifiNetworks = wifiList,
                    apps = appsList,
                    settingsSnapshot = settingsSnap,
                    grapheneAuditNotes = manifest?.grapheneAuditNotes.orEmpty(),
                    availableCategories = inferredCategories
                )
            }
        }.getOrElse { err ->
            val wrongPass = isWrongPasswordError(err)
            ImportPreview(
                sourceLabel = label,
                isEncrypted = encrypted,
                isUnlocked = false,
                errorMessage = if (wrongPass) "WRONG_PASSWORD" else (err.message ?: "Failed to read archive")
            )
        }
    }

    private fun inspectUsbFolder(
        context: Context,
        treeUri: Uri,
        label: String,
        password: String?
    ): ImportPreview {
        val rootDir = DocumentFile.fromTreeUri(context, treeUri)
            ?: return ImportPreview(sourceLabel = label, errorMessage = "Invalid USB folder URI")

        val manifestDoc = rootDir.findFile("manifest.json")
            ?: return ImportPreview(sourceLabel = label, errorMessage = "manifest.json not found in selected USB folder")

        val isEncrypted = detectEncryptedDoc(context, manifestDoc)
        if (isEncrypted && password.isNullOrBlank()) {
            return ImportPreview(sourceLabel = label, isEncrypted = true, isUnlocked = false)
        }

        return runCatching {
            val manifestBytes = readDocBytes(context, manifestDoc, password)
            val manifest = EtgJson.decodeFromString<ArchiveManifest>(String(manifestBytes, StandardCharsets.UTF_8))
            val wifiBytes = findNestedDoc(rootDir, "wifi/wifi_networks.json")?.let { readDocBytes(context, it, password) }
            val appsBytes = findNestedDoc(rootDir, "apps/apps_manifest.json")?.let { readDocBytes(context, it, password) }
            val settingsBytes = findNestedDoc(rootDir, "settings/system_settings.json")?.let { readDocBytes(context, it, password) }

            val wifiList = WifiExporter.decodeNetworks(wifiBytes)
            val appsList = appsBytes?.let {
                runCatching { EtgJson.decodeFromString<List<AppPackageEntry>>(String(it, StandardCharsets.UTF_8)) }.getOrNull()
            }.orEmpty()
            val settingsSnap = settingsBytes?.let {
                runCatching { EtgJson.decodeFromString<SystemSettingsSnapshot>(String(it, StandardCharsets.UTF_8)) }.getOrNull()
            }

            ImportPreview(
                sourceLabel = label,
                isEncrypted = isEncrypted,
                isUnlocked = true,
                manifest = manifest,
                archiveEntriesCount = manifest.entries.size,
                totalUncompressedBytes = manifest.entries.sumOf { it.sizeBytes },
                wifiNetworks = wifiList,
                apps = appsList,
                settingsSnapshot = settingsSnap,
                grapheneAuditNotes = manifest.grapheneAuditNotes,
                availableCategories = manifest.categories.toSet()
            )
        }.getOrElse { err ->
            ImportPreview(
                sourceLabel = label,
                isEncrypted = isEncrypted,
                isUnlocked = false,
                errorMessage = if (isWrongPasswordError(err)) "WRONG_PASSWORD" else (err.message ?: "USB inspection error")
            )
        }
    }

    fun restore(
        context: Context,
        source: ArchiveSource,
        preview: ImportPreview,
        password: String?,
        selectedCategories: Set<ExportCategory>,
        onProgress: (OperationProgress) -> Unit,

        onlyPaths: Set<String>? = null
    ): ImportResult {
        val tempMetaDir = File(context.cacheDir, "etg_restore_tmp/${UUID.randomUUID()}").apply { mkdirs() }
        val extractedApksBaseDir = File(context.filesDir, "etg_extracted_apks").apply {
            if (onlyPaths == null) deleteRecursively()
            mkdirs()
        }
        if (onlyPaths == null) SinusChatBridge.clearHandoff(context)

        val manifest = preview.manifest
        val mediaByArchivePath = manifest?.mediaIndex?.associateBy { it.archivePath }.orEmpty()
        val expectedShaByPath = manifest?.entries?.associate { it.path to it.sha256 }.orEmpty()
        val aliases = manifest?.entryAliases.orEmpty()
        val brokenEntries = manifest?.failedEntries.orEmpty().toSet()
        val totals = manifest?.mediaIndex.orEmpty().groupingBy { it.category }.eachCount()
        val stats = mutableListOf<CategoryImportStat>()
        val issues = mutableListOf<TransferIssue>()
        fun wanted(path: String) = onlyPaths == null || path in onlyPaths
        fun issue(
            category: ExportCategory?,
            name: String,
            detail: String,
            reason: IssueReason,
            reasonDetail: String = "",
            path: String = "",
            retryable: Boolean = true
        ) {
            issues += TransferIssue(
                phase = IssuePhase.IMPORT,
                category = category,
                itemName = name,
                detail = detail,
                reason = reason,
                reasonDetail = reasonDetail,
                retryable = retryable && path.isNotBlank(),
                categoryTotal = category?.let { totals[it] } ?: 0,
                archivePath = path
            )
        }
        if (onlyPaths == null) {
            manifest?.exportIssues.orEmpty().forEach { issues += it.copy(retryable = false) }
        }

        var photosSuccess = 0
        var photosFail = 0
        var audioSuccess = 0
        var audioFail = 0
        var docsSuccess = 0
        var docsFail = 0
        var vaultSuccess = 0
        var vaultFail = 0
        var chatsSuccess = 0
        var chatsFail = 0
        var hashMismatches = 0
        val extractedApkDirs = mutableMapOf<String, MutableList<File>>()

        val totalEntriesToProcess = maxOf(1, preview.archiveEntriesCount)
        var processedEntries = 0

        try {
            fun handleEntryBody(cleanPath: String, rawStream: InputStream) {
                onProgress(
                    OperationProgress(
                        phase = OperationPhase.IMPORTING,
                        currentItemLabel = cleanPath.substringAfterLast('/'),
                        processedItems = processedEntries,
                        totalItems = totalEntriesToProcess
                    )
                )

                val category = detectCategoryForPath(cleanPath, mediaByArchivePath)
                if (category != null && category !in selectedCategories) {
                    drainAndVerify(rawStream, expectedShaByPath[cleanPath])
                    return
                }

                when {
                    cleanPath.startsWith("contacts/") ||
                        cleanPath.startsWith("calendar/") ||
                        cleanPath == "messages/sms_mms.json" ||
                        cleanPath == MessagesAndCallsExporter.BLOCKED_NUMBERS_PATH ||
                        cleanPath == "calls/call_log.json" ||
                        cleanPath == "settings/system_settings.json" ||
                        cleanPath == "settings/wallpaper.png" -> {
                        val outFile = File(tempMetaDir, cleanPath)
                        outFile.parentFile?.mkdirs()
                        val actualSha = outFile.outputStream().use { copyWithSha256(rawStream, it) }
                        val expSha = expectedShaByPath[cleanPath]
                        if (expSha != null && !expSha.equals(actualSha, ignoreCase = true)) {
                            hashMismatches++
                            outFile.delete()
                            issue(category, cleanPath.substringAfterLast('/'), cleanPath, IssueReason.CORRUPTED, "SHA-256", cleanPath)
                        }
                    }

                    cleanPath.startsWith("apps/apks/") && ExportCategory.APPS_AND_APKS in selectedCategories -> {
                        val rel = cleanPath.removePrefix("apps/apks/")
                        val pkg = rel.substringBefore('/')
                        val outFile = File(extractedApksBaseDir, rel)
                        outFile.parentFile?.mkdirs()
                        val actualSha = outFile.outputStream().use { copyWithSha256(rawStream, it) }
                        val expSha = expectedShaByPath[cleanPath]
                        if (expSha != null && !expSha.equals(actualSha, ignoreCase = true)) {
                            hashMismatches++
                            outFile.delete()
                            val label = preview.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
                            issue(ExportCategory.APPS_AND_APKS, label, pkg, IssueReason.CORRUPTED, "SHA-256", cleanPath)
                        } else {
                            extractedApkDirs.getOrPut(pkg) { mutableListOf() } += outFile
                        }
                    }

                    mediaByArchivePath.containsKey(cleanPath) -> {
                        val mediaEntry = mediaByArchivePath.getValue(cleanPath)
                        val ok = if (mediaEntry.category == ExportCategory.CHAT_BACKUPS && SinusChatBridge.isBackup(mediaEntry.fileName)) {
                            val kept = SinusChatBridge.handoffFile(context, mediaEntry.fileName)
                            val sha = kept.outputStream().use { copyWithSha256(rawStream, it) }
                            val expSha = expectedShaByPath[cleanPath]
                            if (expSha != null && !expSha.equals(sha, ignoreCase = true)) {
                                kept.delete()
                                hashMismatches++
                                issue(mediaEntry.category, mediaEntry.fileName, mediaEntry.relativePath, IssueReason.CORRUPTED, "SHA-256", cleanPath)
                                return
                            }
                            kept.inputStream().use { MediaAndFilesExporter.restoreMediaOrFileEntry(context, mediaEntry, it) }
                        } else {
                            MediaAndFilesExporter.restoreMediaOrFileEntry(context, mediaEntry, rawStream)
                        }
                        if (!ok) {
                            issue(
                                mediaEntry.category, mediaEntry.fileName, mediaEntry.relativePath,
                                IssueReason.WRITE_FAILED, path = cleanPath
                            )
                        }
                        when (mediaEntry.category) {
                            ExportCategory.PHOTOS_VIDEOS -> if (ok) photosSuccess++ else photosFail++
                            ExportCategory.AUDIO_RINGTONES -> if (ok) audioSuccess++ else audioFail++
                            ExportCategory.DOCUMENTS_DOWNLOADS -> if (ok) docsSuccess++ else docsFail++
                            ExportCategory.VAULT_KEYS_2FA -> if (ok) vaultSuccess++ else vaultFail++
                            ExportCategory.CHAT_BACKUPS -> if (ok) chatsSuccess++ else chatsFail++
                            else -> Unit
                        }
                    }

                    else -> Unit
                }
            }

            fun handleStreamEntry(path: String, rawStream: InputStream) {
                processedEntries++
                val rawPath = path.trimStart('/')

                if (rawPath in brokenEntries) return
                val cleanPath = aliases[rawPath] ?: rawPath
                if (!wanted(cleanPath)) return
                try {
                    handleEntryBody(cleanPath, rawStream)
                } catch (t: Throwable) {
                    if (IssueLog.isFatal(t) || t is ZipException) throw t
                    val (reason, detail) = IssueLog.reasonOf(t)
                    val media = mediaByArchivePath[cleanPath]
                    issue(
                        category = media?.category ?: detectCategoryForPath(cleanPath, mediaByArchivePath),
                        name = media?.fileName ?: cleanPath.substringAfterLast('/'),
                        detail = media?.relativePath ?: cleanPath.substringBeforeLast('/', ""),
                        reason = reason,
                        reasonDetail = detail,
                        path = cleanPath
                    )
                }
            }

            try {
                if (source is ArchiveSource.UsbFolder) {
                    val rootDir = DocumentFile.fromTreeUri(context, source.treeUri)
                        ?: throw IllegalStateException("USB root inaccessible")
                    traverseUsbFolder(context, rootDir, "", password, ::handleStreamEntry)
                } else {
                    openPlainZipStream(context, source, password).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory) {
                                handleStreamEntry(entry.name, zip)
                            }
                            zip.closeEntry()
                        }
                    }
                }
            } catch (t: Throwable) {
                if (IssueLog.isFatal(t) || isWrongPasswordError(t)) throw t

                val (_, detail) = IssueLog.reasonOf(t)
                issue(
                    category = null,
                    name = "${processedEntries} / $totalEntriesToProcess",
                    detail = "",
                    reason = IssueReason.READ_INTERRUPTED,
                    reasonDetail = detail,
                    path = if (onlyPaths == null) "*" else onlyPaths.joinToString("\n")
                )
            }

            if (ExportCategory.PHOTOS_VIDEOS in selectedCategories) {
                stats += CategoryImportStat(ExportCategory.PHOTOS_VIDEOS, photosSuccess, failedCount = photosFail)
            }
            if (ExportCategory.AUDIO_RINGTONES in selectedCategories) {
                stats += CategoryImportStat(ExportCategory.AUDIO_RINGTONES, audioSuccess, failedCount = audioFail)
            }
            if (ExportCategory.DOCUMENTS_DOWNLOADS in selectedCategories) {
                stats += CategoryImportStat(ExportCategory.DOCUMENTS_DOWNLOADS, docsSuccess, failedCount = docsFail)
            }
            if (ExportCategory.VAULT_KEYS_2FA in selectedCategories) {
                stats += CategoryImportStat(ExportCategory.VAULT_KEYS_2FA, vaultSuccess, failedCount = vaultFail)
            }
            if (ExportCategory.CHAT_BACKUPS in selectedCategories) {
                stats += CategoryImportStat(ExportCategory.CHAT_BACKUPS, chatsSuccess, failedCount = chatsFail)
            }

            if (ExportCategory.CONTACTS in selectedCategories && (wanted("contacts/contacts.json") || wanted("contacts/contacts.vcf"))) {
                val jsonBytes = File(tempMetaDir, "contacts/contacts.json").takeIf { it.exists() }?.readBytes()
                val vcfBytes = File(tempMetaDir, "contacts/contacts.vcf").takeIf { it.exists() }?.readBytes()
                stats += ContactsExporter.import(
                    context = context,
                    jsonBytes = jsonBytes,
                    vcfBytes = vcfBytes,
                    photoResolver = { rel -> File(tempMetaDir, rel).takeIf { it.exists() }?.readBytes() }
                ) { p, t, lbl ->
                    onProgress(OperationProgress(OperationPhase.IMPORTING, ExportCategory.CONTACTS, lbl, p, t))
                }
            }

            if (ExportCategory.CALENDAR in selectedCategories && wanted("calendar/calendar.json")) {
                val jsonBytes = File(tempMetaDir, "calendar/calendar.json").takeIf { it.exists() }?.readBytes()
                stats += CalendarExporter.import(context, jsonBytes) { p, t, lbl ->
                    onProgress(OperationProgress(OperationPhase.IMPORTING, ExportCategory.CALENDAR, lbl, p, t))
                }
            }

            var needsSmsRole = false
            if (ExportCategory.MESSAGES_SMS_MMS in selectedCategories && wanted("messages/sms_mms.json")) {
                val jsonBytes = File(tempMetaDir, "messages/sms_mms.json").takeIf { it.exists() }?.readBytes()
                val (smsStat, needRole) = MessagesAndCallsExporter.importSmsMms(context, jsonBytes) { p, t, lbl ->
                    onProgress(OperationProgress(OperationPhase.IMPORTING, ExportCategory.MESSAGES_SMS_MMS, lbl, p, t))
                }
                needsSmsRole = needRole
                stats += smsStat
                val blockedBytes = File(tempMetaDir, MessagesAndCallsExporter.BLOCKED_NUMBERS_PATH)
                    .takeIf { it.exists() }?.readBytes()
                MessagesAndCallsExporter.importBlockedNumbers(context, blockedBytes)?.let { (blockedStat, needRoleBlocked) ->
                    stats += blockedStat
                    needsSmsRole = needsSmsRole || needRoleBlocked
                }
            }

            if (ExportCategory.CALL_LOG in selectedCategories && wanted("calls/call_log.json")) {
                val jsonBytes = File(tempMetaDir, "calls/call_log.json").takeIf { it.exists() }?.readBytes()
                stats += MessagesAndCallsExporter.importCallLog(context, jsonBytes) { p, t, lbl ->
                    onProgress(OperationProgress(OperationPhase.IMPORTING, ExportCategory.CALL_LOG, lbl, p, t))
                }
            }

            if (ExportCategory.SYSTEM_SETTINGS in selectedCategories && onlyPaths == null) {
                val snapFile = File(tempMetaDir, "settings/system_settings.json")
                val snap = if (snapFile.exists()) {
                    runCatching { EtgJson.decodeFromString<SystemSettingsSnapshot>(snapFile.readText()) }.getOrNull()
                } else preview.settingsSnapshot
                val wallBytes = File(tempMetaDir, "settings/wallpaper.png").takeIf { it.exists() }?.readBytes()
                val (settingsStat, settingIssues) = SettingsExporter.importSettings(context, snap, wallBytes)
                stats += settingsStat
                issues += settingIssues
            }

            val wholeFilePaths = mapOf(
                ExportCategory.CONTACTS to "contacts/contacts.json",
                ExportCategory.CALENDAR to "calendar/calendar.json",
                ExportCategory.MESSAGES_SMS_MMS to "messages/sms_mms.json",
                ExportCategory.CALL_LOG to "calls/call_log.json"
            )
            stats.filter { it.failedCount > 0 && it.category in wholeFilePaths }.forEach { st ->
                issues += TransferIssue(
                    phase = IssuePhase.IMPORT,
                    category = st.category,
                    itemName = "${st.failedCount}",
                    detail = st.notes,
                    reason = IssueReason.PARTIAL_IMPORT,
                    retryable = true,
                    categoryTotal = st.importedCount + st.failedCount + st.skippedDuplicates,
                    archivePath = wholeFilePaths.getValue(st.category)
                )
            }

            val wifiToSave = if (ExportCategory.WIFI_NETWORKS in selectedCategories) {
                stats += CategoryImportStat(
                    ExportCategory.WIFI_NETWORKS,
                    importedCount = preview.wifiNetworks.size,
                    notes = "Use 1-tap system dialog below to save networks"
                )
                preview.wifiNetworks
            } else emptyList()

            val appsForPanel = if (ExportCategory.APPS_AND_APKS in selectedCategories) {
                val withExtracted = preview.apps.map { app ->
                    val localCount = extractedApkDirs[app.packageName]?.size ?: 0
                    if (localCount > 0) app else app.copy(apkRelativePaths = emptyList())
                }
                stats += CategoryImportStat(
                    ExportCategory.APPS_AND_APKS,
                    importedCount = extractedApkDirs.size,
                    notes = if (extractedApkDirs.isNotEmpty()) {
                        "${extractedApkDirs.size} APK bundles ready to install below"
                    } else "${preview.apps.size} app entries inspected"
                )
                withExtracted
            } else emptyList()

            if (hashMismatches > 0) {
                stats += CategoryImportStat(
                    category = ExportCategory.SYSTEM_SETTINGS,
                    failedCount = hashMismatches,
                    notes = "SHA-256 verification flagged $hashMismatches corrupted entry(ies)"
                )
            }

            return ImportResult(
                categoryStats = stats,
                wifiNetworksToSave = wifiToSave,
                needsSmsRoleForRestore = needsSmsRole,
                restoredSmsPendingCount = manifest?.summaryCounts?.get(ExportCategory.MESSAGES_SMS_MMS.name) ?: 0,
                extractedApkEntries = appsForPanel,
                settingsSnapshot = preview.settingsSnapshot,
                completedAtIso = Instant.now().toString(),
                issues = issues
            )
        } finally {
            tempMetaDir.deleteRecursively()
        }
    }

    fun resolveExtractedApkFiles(context: Context, packageName: String): List<File> {
        val dir = File(context.filesDir, "etg_extracted_apks/$packageName")
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") }
            ?.sortedBy { if (it.name == "base.apk") "" else it.name }
            .orEmpty()
    }

    private fun detectCategoryForPath(
        path: String,
        mediaMap: Map<String, org.etg.migration.model.MediaFileEntry>
    ): ExportCategory? {
        mediaMap[path]?.let { return it.category }
        return when {
            path.startsWith("contacts/") -> ExportCategory.CONTACTS
            path.startsWith("calendar/") -> ExportCategory.CALENDAR
            path.startsWith("messages/") -> ExportCategory.MESSAGES_SMS_MMS
            path.startsWith("calls/") -> ExportCategory.CALL_LOG
            path.startsWith("wifi/") -> ExportCategory.WIFI_NETWORKS
            path.startsWith("apps/") -> ExportCategory.APPS_AND_APKS
            path.startsWith("settings/") -> ExportCategory.SYSTEM_SETTINGS
            path.startsWith("chats/") -> ExportCategory.CHAT_BACKUPS
            path.startsWith("vault/") -> ExportCategory.VAULT_KEYS_2FA
            path.startsWith("documents/") -> ExportCategory.DOCUMENTS_DOWNLOADS
            path.startsWith("media/audio/") -> ExportCategory.AUDIO_RINGTONES
            path.startsWith("media/") -> ExportCategory.PHOTOS_VIDEOS
            else -> null
        }
    }

    private fun detectEncryptedArchive(context: Context, source: ArchiveSource): Boolean {
        val buf = ByteArray(EtgCryptoFormat.HEADER_SIZE)
        val stream = openRawStream(context, source)
        val n = stream.use { it.read(buf) }
        return n >= EtgCryptoFormat.HEADER_SIZE && EtgCryptoFormat.isEncryptedEtgHeader(buf)
    }

    private fun detectEncryptedDoc(context: Context, doc: DocumentFile): Boolean {
        val buf = ByteArray(EtgCryptoFormat.HEADER_SIZE)
        val n = context.contentResolver.openInputStream(doc.uri)?.use { it.read(buf) } ?: 0
        return n >= EtgCryptoFormat.HEADER_SIZE && EtgCryptoFormat.isEncryptedEtgHeader(buf)
    }

    private fun openRawStream(context: Context, source: ArchiveSource): InputStream = when (source) {
        is ArchiveSource.SafUri -> context.contentResolver.openInputStream(source.uri)
            ?: throw IllegalStateException("Cannot open archive URI")
        is ArchiveSource.LocalFile -> source.file.inputStream()
        is ArchiveSource.UsbFolder -> throw IllegalArgumentException("Folder has no single raw stream")
    }

    private fun openPlainZipStream(context: Context, source: ArchiveSource, password: String?): ZipInputStream {
        val raw = BufferedInputStream(openRawStream(context, source), 64 * 1024)
        raw.mark(EtgCryptoFormat.HEADER_SIZE + 4)
        val header = ByteArray(EtgCryptoFormat.HEADER_SIZE)
        val n = raw.read(header)
        raw.reset()
        val plainStream: InputStream = if (n >= EtgCryptoFormat.HEADER_SIZE && EtgCryptoFormat.isEncryptedEtgHeader(header)) {
            EtgChunkedCipherInputStream.open(raw, password.orEmpty())
        } else {
            raw
        }
        return ZipInputStream(BufferedInputStream(plainStream, 64 * 1024))
    }

    private fun readDocBytes(context: Context, doc: DocumentFile, password: String?): ByteArray {
        val raw = BufferedInputStream(
            context.contentResolver.openInputStream(doc.uri) ?: throw IllegalStateException("Cannot open ${doc.name}"),
            64 * 1024
        )
        raw.mark(EtgCryptoFormat.HEADER_SIZE + 4)
        val header = ByteArray(EtgCryptoFormat.HEADER_SIZE)
        val n = raw.read(header)
        raw.reset()
        val stream: InputStream = if (n >= EtgCryptoFormat.HEADER_SIZE && EtgCryptoFormat.isEncryptedEtgHeader(header)) {
            EtgChunkedCipherInputStream.open(raw, password.orEmpty())
        } else {
            raw
        }
        return stream.use { it.readBytes() }
    }

    private fun findNestedDoc(root: DocumentFile, relPath: String): DocumentFile? {
        var cur: DocumentFile = root
        for (part in relPath.split('/').filter { it.isNotBlank() }) {
            cur = cur.findFile(part) ?: return null
        }
        return cur
    }

    private fun traverseUsbFolder(
        context: Context,
        dir: DocumentFile,
        prefix: String,
        password: String?,
        handler: (String, InputStream) -> Unit
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                traverseUsbFolder(context, child, path, password, handler)
            } else if (child.isFile) {
                val raw = BufferedInputStream(
                    context.contentResolver.openInputStream(child.uri) ?: continue,
                    64 * 1024
                )
                raw.mark(EtgCryptoFormat.HEADER_SIZE + 4)
                val header = ByteArray(EtgCryptoFormat.HEADER_SIZE)
                val n = raw.read(header)
                raw.reset()
                val stream: InputStream = if (n >= EtgCryptoFormat.HEADER_SIZE && EtgCryptoFormat.isEncryptedEtgHeader(header)) {
                    EtgChunkedCipherInputStream.open(raw, password.orEmpty())
                } else raw
                stream.use { handler(path, it) }
            }
        }
    }

    private fun copyWithSha256(input: InputStream, output: java.io.OutputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
            output.write(buf, 0, n)
        }
        return Sha256Helper.toHex(md.digest())
    }

    private fun drainAndVerify(input: InputStream, expectedSha: String?) {
        val md = if (expectedSha != null) MessageDigest.getInstance("SHA-256") else null
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md?.update(buf, 0, n)
        }
    }

    private fun isWrongPasswordError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is AEADBadTagException) return true
            cur = cur.cause
        }
        return false
    }
}
