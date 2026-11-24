package org.etg.migration.data.exporter

import org.etg.migration.R
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.encodeToString
import org.etg.migration.data.archive.ArchiveSink
import org.etg.migration.data.archive.IssueLog
import org.etg.migration.data.archive.attempt
import org.etg.migration.model.IssuePhase
import org.etg.migration.model.IssueReason
import org.etg.migration.model.TransferIssue
import org.etg.migration.data.archive.EtgJson
import org.etg.migration.model.AppPackageEntry
import org.etg.migration.model.CategoryScanInfo
import org.etg.migration.model.CategoryStatus
import org.etg.migration.model.ExportCategory
import org.etg.migration.service.ApkInstallStatusReceiver
import java.io.File
import java.nio.charset.StandardCharsets

object AppsExporter {
    private val BANKING_OR_INTEGRITY_KEYWORDS = listOf(
        "bank", "pkobp", "pekao", "mbank", "ing", "santander", "millennium", "alior",
        "revolut", "blik", "mobywatel", "gov.pl", "paypal", "wise", "monzo", "chase",
        "citibank", "hsbc", "n26", "wallet", "gpay", "netflix", "mcdonald"
    )

    private val FOSS_ALTERNATIVES = mapOf(
        "com.android.chrome" to "Vanadium (built into GrapheneOS) / Mull / Fennec",
        "com.google.android.apps.photos" to "Fossify Gallery / Immich",
        "com.google.android.apps.maps" to "Organic Maps / OsmAnd~",
        "com.google.android.gm" to "Thunderbird / K-9 Mail / FairEmail",
        "com.google.android.apps.authenticator2" to "Aegis Authenticator / Ente Auth",
        "com.authy.authy" to "Aegis Authenticator / Ente Auth",
        "com.azure.authenticator" to "Aegis Authenticator (keep work profile if required)",
        "com.google.android.youtube" to "NewPipe / PipePipe / Grayjay",
        "com.google.android.inputmethod.latin" to "HeliBoard / FUTO Keyboard",
        "com.google.android.apps.docs" to "Nextcloud / Syncthing",
        "com.android.vending" to "Obtainium + F-Droid / Droid-ify + Aurora Store"
    )

    fun scan(context: Context, includeApks: Boolean): Pair<CategoryScanInfo, List<AppPackageEntry>> {
        val pm = context.packageManager
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrElse { emptyList() }

        val entries = mutableListOf<AppPackageEntry>()
        for (app in apps) {
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (app.packageName == context.packageName) continue

            val pkgInfo = runCatching { pm.getPackageInfo(app.packageName, 0) }.getOrNull()
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
            val versionName = pkgInfo?.versionName.orEmpty()
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo?.longVersionCode ?: 0L
            } else {
                @Suppress("DEPRECATION")
                pkgInfo?.versionCode?.toLong() ?: 0L
            }

            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(app.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(app.packageName)
                }
            }.getOrNull()

            val apkFiles = collectSourceApks(app)
            val totalApkBytes = apkFiles.sumOf { it.length() }
            val lowerPkg = app.packageName.lowercase()
            val lowerLabel = label.lowercase()
            val isBanking = BANKING_OR_INTEGRITY_KEYWORDS.any {
                lowerPkg.contains(it) || lowerLabel.contains(it)
            }
            val fossAlt = FOSS_ALTERNATIVES[app.packageName] ?: ""

            entries += AppPackageEntry(
                packageName = app.packageName,
                label = label,
                versionName = versionName,
                versionCode = versionCode,
                installerPackage = installer,
                totalBytes = totalApkBytes,
                apkRelativePaths = apkFiles.mapIndexed { idx, _ ->
                    val name = if (idx == 0) "base.apk" else "split_$idx.apk"
                    "apps/apks/${app.packageName}/$name"
                },
                isBankingOrPlayDependent = isBanking,
                openSourceAlternatives = fossAlt
            )
        }

        val sorted = entries.sortedBy { it.label.lowercase() }
        val estimatedBytes = if (includeApks) {
            sorted.sumOf { it.totalBytes }
        } else {
            sorted.size * 600L
        }

        val scanInfo = CategoryScanInfo(
            category = ExportCategory.APPS_AND_APKS,
            itemCount = sorted.size,
            estimatedBytes = estimatedBytes,
            status = if (sorted.isNotEmpty()) CategoryStatus.READY else CategoryStatus.EMPTY,
            detailSummary = if (includeApks) context.getString(R.string.scan_apps_apks, sorted.size) else context.getString(R.string.scan_apps_list, sorted.size)
        )
        return scanInfo to sorted
    }

    fun export(
        context: Context,
        includeApks: Boolean,
        sink: ArchiveSink,
        excludedPackages: Set<String> = emptySet(),
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        val pm = context.packageManager
        val entries = scan(context, includeApks).second.filter { it.packageName !in excludedPackages }

        val jsonBytes = EtgJson.encodeToString(entries).toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("apps/apps_manifest.json", ExportCategory.APPS_AND_APKS, jsonBytes, compress = true)

        val guideTxt = buildString {
            appendLine("# ETG Installed Apps Manifest (${entries.size} user apps)")
            appendLine("# Recommended GrapheneOS app sources: Accrescent, F-Droid / Droid-ify, Obtainium, Aurora Store (sandboxed), Sandboxed Google Play")
            appendLine()
            for (app in entries) {
                appendLine("${app.label} | ${app.packageName} | v${app.versionName} (${app.versionCode}) | source=${app.installerPackage ?: "sideload"}")
                if (app.isBankingOrPlayDependent) {
                    appendLine("  [!] Banking/Play-Integrity sensitive: test inside a dedicated profile with Sandboxed Google Play.")
                }
                if (app.openSourceAlternatives.isNotBlank()) {
                    appendLine("  [FOSS Alternative]: ${app.openSourceAlternatives}")
                }
            }
        }.toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("apps/apps_list.txt", ExportCategory.APPS_AND_APKS, guideTxt, compress = true)

        if (includeApks) {
            val total = entries.size
            for ((idx, entry) in entries.withIndex()) {
                onItemProgress(idx + 1, total, entry.label)
                val appInfo = runCatching { pm.getApplicationInfo(entry.packageName, 0) }.getOrNull() ?: continue
                val apkFiles = collectSourceApks(appInfo)
                fun copyApks(): Pair<IssueReason, String>? {
                    for ((apkIdx, file) in apkFiles.withIndex()) {
                        val fileName = if (apkIdx == 0) "base.apk" else "split_$apkIdx.apk"
                        val archivePath = "apps/apks/${entry.packageName}/$fileName"
                        if (!file.exists() || !file.canRead()) return IssueReason.NO_PERMISSION to file.name
                        attempt {
                            sink.copyFile(archivePath, ExportCategory.APPS_AND_APKS, file, compress = false)
                        }.onFailure { return IssueLog.reasonOf(it) }
                    }
                    return null
                }
                copyApks()?.let { (reason, detail) ->
                    sink.issues.report(
                        TransferIssue(
                            phase = IssuePhase.EXPORT,
                            category = ExportCategory.APPS_AND_APKS,
                            itemName = entry.label,
                            detail = entry.packageName,
                            reason = reason,
                            reasonDetail = detail,
                            categoryTotal = total
                        )
                    ) { copyApks() }
                }
            }
        }
        return entries.size
    }

    fun installApkFiles(context: Context, apkFiles: List<File>): Result<Unit> = runCatching {
        val validFiles = apkFiles.filter { it.exists() && it.length() > 0L }
        require(validFiles.isNotEmpty()) { "No valid APK files found for installation" }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val totalSize = validFiles.sumOf { it.length() }
        params.setSize(totalSize)

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            for ((idx, file) in validFiles.withIndex()) {
                val splitName = if (idx == 0) "base.apk" else file.name
                session.openWrite(splitName, 0, file.length()).use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                    session.fsync(out)
                }
            }
            val callbackIntent = Intent(context, ApkInstallStatusReceiver::class.java).apply {
                action = ApkInstallStatusReceiver.ACTION_INSTALL_STATUS
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun collectSourceApks(app: ApplicationInfo): List<File> {
        val list = mutableListOf<File>()
        app.sourceDir?.let { list += File(it) }
        app.splitSourceDirs?.forEach { split -> list += File(split) }
        return list
    }
}
