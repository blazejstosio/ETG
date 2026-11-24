package org.etg.migration.data.exporter

import org.etg.migration.R
import android.annotation.SuppressLint
import android.app.UiModeManager
import android.app.WallpaperManager
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import android.provider.UserDictionary
import kotlinx.serialization.encodeToString
import org.etg.migration.data.PermissionHelper
import org.etg.migration.data.archive.ArchiveSink
import org.etg.migration.data.archive.EtgJson
import org.etg.migration.data.shizuku.ShizukuBridge
import org.etg.migration.model.AppPackageEntry
import org.etg.migration.model.BluetoothDeviceEntry
import org.etg.migration.model.CategoryImportStat
import org.etg.migration.model.CategoryScanInfo
import org.etg.migration.model.CategoryStatus
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.GrapheneReadinessItem
import org.etg.migration.model.ReadinessSeverity
import org.etg.migration.model.SystemAuditItem
import org.etg.migration.model.SystemSettingsSnapshot
import org.etg.migration.model.IssuePhase
import org.etg.migration.model.IssueReason
import org.etg.migration.model.TransferIssue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

object SettingsExporter {
    fun scan(context: Context): CategoryScanInfo {
        val snapshot = collectSnapshot(context, includeWallpaperBytes = false).first
        val count = snapshot.writableSystemSettings.size + snapshot.extendedSettings.size +
            snapshot.systemAuditItems.size +
            snapshot.pairedBluetooth.size +
            (if (snapshot.hasWallpaper) 1 else 0)
        return CategoryScanInfo(
            category = ExportCategory.SYSTEM_SETTINGS,
            itemCount = count,
            estimatedBytes = if (snapshot.hasWallpaper) 1_500_000L else 12_000L,
            status = CategoryStatus.READY,
            detailSummary = buildString {
                append(context.getString(R.string.scan_prefs, snapshot.writableSystemSettings.size + snapshot.extendedSettings.size))
                val tones = listOf(snapshot.ringtone, snapshot.notificationSound, snapshot.alarmSound).count { it != null }
                if (tones > 0) append(" • ♪ $tones")
                append(" • ${snapshot.pairedBluetooth.size} BT")
            }
        )
    }

    fun collectSnapshot(
        context: Context,
        includeWallpaperBytes: Boolean = false
    ): Pair<SystemSettingsSnapshot, ByteArray?> {
        val cr = context.contentResolver
        val allSettings = SettingsCatalog.readAll(context)
        val writable = LinkedHashMap(allSettings.filterKeys { SettingsCatalog.def(it)?.ns == SettingsCatalog.Ns.SYSTEM })
        val extended = LinkedHashMap(allSettings.filterKeys { it !in writable })

        val auditItems = mutableListOf<SystemAuditItem>()
        val privateDnsMode = runCatching { Settings.Global.getString(cr, "private_dns_mode") }.getOrNull().orEmpty()
        val privateDnsSpec = runCatching { Settings.Global.getString(cr, "private_dns_specifier") }.getOrNull().orEmpty()
        if (privateDnsMode.isNotBlank()) {
            auditItems += SystemAuditItem(
                key = "private_dns",
                label = "Private DNS",
                value = if (privateDnsSpec.isNotBlank()) "$privateDnsMode ($privateDnsSpec)" else privateDnsMode,
                settingsAction = Settings.ACTION_WIRELESS_SETTINGS,
                categoryHint = "Network & internet -> Private DNS"
            )
        }

        runCatching {
            val uiMode = context.getSystemService(UiModeManager::class.java)?.nightMode
            val nightLabel = when (uiMode) {
                UiModeManager.MODE_NIGHT_YES -> "Dark"
                UiModeManager.MODE_NIGHT_NO -> "Light"
                UiModeManager.MODE_NIGHT_AUTO -> "Auto"
                else -> "System"
            }
            auditItems += SystemAuditItem(
                key = "night_mode",
                label = "Theme / Night Mode",
                value = nightLabel,
                settingsAction = Settings.ACTION_DISPLAY_SETTINGS,
                categoryHint = "Display -> Dark theme"
            )
        }

        writable[Settings.System.SCREEN_OFF_TIMEOUT]?.toLongOrNull()?.let { ms ->
            auditItems += SystemAuditItem(
                key = "screen_timeout",
                label = "Screen Timeout",
                value = "${ms / 1000}s",
                settingsAction = Settings.ACTION_DISPLAY_SETTINGS,
                categoryHint = "Display -> Screen timeout"
            )
        }
        writable[Settings.System.FONT_SCALE]?.let { scale ->
            auditItems += SystemAuditItem(
                key = "font_scale",
                label = "Font Scale",
                value = "${scale}x",
                settingsAction = Settings.ACTION_DISPLAY_SETTINGS,
                categoryHint = "Display -> Display size and text"
            )
        }
        runCatching {
            Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)?.let { ime ->
                auditItems += SystemAuditItem(
                    key = "default_ime",
                    label = "Keyboard (IME)",
                    value = ime.substringBefore('/'),
                    settingsAction = Settings.ACTION_INPUT_METHOD_SETTINGS,
                    categoryHint = "System -> Keyboard -> On-screen keyboard"
                )
            }
        }

        val words = mutableListOf<String>()
        runCatching {
            cr.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD),
                null,
                null,
                null
            )?.use { c ->
                val wIdx = c.getColumnIndex(UserDictionary.Words.WORD)
                while (c.moveToNext()) {
                    c.getString(wIdx)?.takeIf { it.isNotBlank() }?.let { words += it }
                }
            }
        }

        val btList = mutableListOf<BluetoothDeviceEntry>()
        if (PermissionHelper.hasBluetoothConnect(context)) {
            runCatching {
                val bm = context.getSystemService(BluetoothManager::class.java)
                @SuppressLint("MissingPermission")
                val bonded = bm?.adapter?.bondedDevices
                bonded?.forEach { dev ->
                    @SuppressLint("MissingPermission")
                    val name = dev.name ?: dev.address
                    @SuppressLint("MissingPermission")
                    val devClass = dev.bluetoothClass?.deviceClass ?: 0
                    btList += BluetoothDeviceEntry(
                        name = name,
                        address = dev.address,
                        deviceClass = devClass
                    )
                }
            }
        }

        val wallpaperBytes = if (includeWallpaperBytes) extractWallpaperPng(context) else null
        val hasWallpaperFlag = wallpaperBytes != null ||
            (!includeWallpaperBytes && runCatching {
                WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) >= 0
            }.getOrDefault(false))
        val snapshot = SystemSettingsSnapshot(
            writableSystemSettings = writable,
            systemAuditItems = auditItems,
            personalDictionaryWords = words.distinct(),
            pairedBluetooth = btList,
            hasWallpaper = hasWallpaperFlag,
            userLocale = Locale.getDefault().toLanguageTag(),
            privateDnsMode = privateDnsMode,
            privateDnsSpecifier = privateDnsSpec,
            ringtone = SoundRefResolver.describeDefault(context, RingtoneManager.TYPE_RINGTONE),
            notificationSound = SoundRefResolver.describeDefault(context, RingtoneManager.TYPE_NOTIFICATION),
            alarmSound = SoundRefResolver.describeDefault(context, RingtoneManager.TYPE_ALARM),
            extendedSettings = extended
        )
        return snapshot to wallpaperBytes
    }

    fun buildGrapheneReadinessAudit(
        context: Context,
        installedApps: List<AppPackageEntry>
    ): List<GrapheneReadinessItem> {
        val items = mutableListOf<GrapheneReadinessItem>()
        val bankingApps = installedApps.filter { it.isBankingOrPlayDependent }
        if (bankingApps.isNotEmpty()) {
            items += GrapheneReadinessItem(
                id = "banking_play_integrity",
                title = "Banking & Play-Integrity Apps (${bankingApps.size})",
                severity = ReadinessSeverity.CRITICAL,
                description = "Apps like ${bankingApps.take(5).joinToString { it.label }} may require GrapheneOS Sandboxed Google Play (in a secondary profile) and Exploit Protection Compatibility Mode.",
                relatedPackages = bankingApps.map { it.packageName }
            )
        }

        val twoFaApps = installedApps.filter { app ->
            val p = app.packageName.lowercase()
            p.contains("authenticator") || p.contains("authy") || p.contains("2fa") || p.contains("okta") || p.contains("duo")
        }
        if (twoFaApps.isNotEmpty()) {
            items += GrapheneReadinessItem(
                id = "2fa_migration",
                title = "2FA Authenticator Apps Detected (${twoFaApps.size})",
                severity = ReadinessSeverity.CRITICAL,
                description = "Export 2FA secrets (QR codes / backup file) from ${twoFaApps.joinToString { it.label }} BEFORE flashing GrapheneOS! Recommended FOSS target: Aegis or Ente Auth.",
                relatedPackages = twoFaApps.map { it.packageName }
            )
        }

        val signalInstalled = installedApps.any {
            it.packageName == "org.thoughtcrime.securesms" || it.packageName == "im.molly.app"
        }
        if (signalInstalled) {
            items += GrapheneReadinessItem(
                id = "signal_passphrase",
                title = "Signal / Molly 30-Digit Backup Key",
                severity = ReadinessSeverity.WARN,
                description = "Signal encrypts local backups with a 30-digit passphrase shown only when enabling backups. Record it before wiping.",
                relatedPackages = listOf("org.thoughtcrime.securesms", "im.molly.app")
            )
        }

        val whatsappInstalled = installedApps.any { it.packageName.startsWith("com.whatsapp") }
        if (whatsappInstalled) {
            items += GrapheneReadinessItem(
                id = "whatsapp_local_backup",
                title = "WhatsApp Local Backup (No Google Drive)",
                severity = ReadinessSeverity.WARN,
                description = "GrapheneOS has no Google Drive by default. Perform a Local Backup in WhatsApp and ensure ETG exports Android/media/com.whatsapp/WhatsApp/Databases.",
                relatedPackages = listOf("com.whatsapp")
            )
        }

        items += GrapheneReadinessItem(
            id = "wifi_restriction",
            title = "Wi-Fi Passwords Restriction on Android",
            severity = ReadinessSeverity.INFO,
            description = "Android blocks apps from reading saved Wi-Fi passwords without root. Use ETG's built-in Wi-Fi QR scanner (from system Wi-Fi Share) or import WifiConfigStore.xml."
        )
        items += GrapheneReadinessItem(
            id = "esim_check",
            title = "Check eSIM Carrier QR Codes",
            severity = ReadinessSeverity.INFO,
            description = "Unlocking bootloader and flashing GrapheneOS wipes user data. Keep your carrier eSIM activation QR handy just in case."
        )
        return items
    }

    fun export(
        context: Context,
        installedApps: List<AppPackageEntry>,
        sink: ArchiveSink,
        excluded: Set<String> = emptySet()
    ): List<GrapheneReadinessItem> {
        val (rawSnapshot, rawWallpaper) = collectSnapshot(context, includeWallpaperBytes = SelectionCatalog.SETTING_WALLPAPER !in excluded)
        val snapshot = SelectionCatalog.filterSnapshot(rawSnapshot, excluded)
        val wallpaperBytes = rawWallpaper.takeIf { snapshot.hasWallpaper }
        val audit = buildGrapheneReadinessAudit(context, installedApps)

        val settingsJson = EtgJson.encodeToString(snapshot).toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("settings/system_settings.json", ExportCategory.SYSTEM_SETTINGS, settingsJson, compress = true)

        val auditJson = EtgJson.encodeToString(audit).toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("settings/graphene_audit.json", ExportCategory.SYSTEM_SETTINGS, auditJson, compress = true)

        if (wallpaperBytes != null) {
            sink.writeBytes("settings/wallpaper.png", ExportCategory.SYSTEM_SETTINGS, wallpaperBytes, compress = false)
        }
        return audit
    }

    fun importSettings(
        context: Context,
        snapshot: SystemSettingsSnapshot?,
        wallpaperBytes: ByteArray?,
        onlyIds: Set<String>? = null
    ): Pair<CategoryImportStat, List<TransferIssue>> {
        if (snapshot == null) {
            return CategoryImportStat(ExportCategory.SYSTEM_SETTINGS, notes = "No system settings snapshot found") to emptyList()
        }
        fun wanted(id: String) = onlyIds == null || id in onlyIds
        var applied = 0
        val issues = mutableListOf<TransferIssue>()
        val total = snapshot.writableSystemSettings.size + snapshot.extendedSettings.size +
            listOf(snapshot.ringtone, snapshot.notificationSound, snapshot.alarmSound).count { it != null } +
            (if (wallpaperBytes != null) 1 else 0)
        fun issue(id: String, name: String, detail: String, reason: IssueReason, reasonDetail: String = "", retryable: Boolean = true) {
            issues += TransferIssue(
                phase = IssuePhase.IMPORT,
                category = ExportCategory.SYSTEM_SETTINGS,
                itemName = name,
                detail = detail,
                reason = reason,
                reasonDetail = reasonDetail,
                retryable = retryable,
                categoryTotal = total,
                settingId = id
            )
        }

        val shizuku = ShizukuBridge.status(context) == ShizukuBridge.Status.READY
        val canWrite = PermissionHelper.canWriteSystemSettings(context)
        val cr = context.contentResolver
        for ((key, value) in snapshot.writableSystemSettings) {
            if (!wanted(key)) continue
            val def = SettingsCatalog.def(key)
            if (def != null && !SettingsCatalog.isValid(def, value)) continue
            val ok = when {
                shizuku -> runCatching { ShizukuBridge.putSetting("system", key, value) }.getOrDefault(false) ||
                    (canWrite && runCatching { Settings.System.putString(cr, key, value) }.getOrDefault(false))
                canWrite -> runCatching { Settings.System.putString(cr, key, value) }.getOrDefault(false)
                else -> {
                    issue(key, key, key, IssueReason.NEEDS_WRITE_SETTINGS)
                    continue
                }
            }
            if (ok) applied++ else issue(key, key, key, IssueReason.REFUSED_BY_SYSTEM)
        }

        for (def in SettingsCatalog.DEFS) {
            val value = snapshot.extendedSettings[def.id] ?: continue
            if (!wanted(def.id)) continue
            if (!shizuku) {
                issue(def.id, def.key, def.key, IssueReason.NEEDS_SHIZUKU)
                continue
            }
            val problem = runCatching { SettingsCatalog.applyWithShizuku(context, def, value) }
                .getOrElse { IssueReason.UNKNOWN to (it.message ?: "") }
            if (problem == null) applied++ else {
                val detail = if (def.kind == SettingsCatalog.Kind.IME) value.substringBefore('/') else def.key
                issue(def.id, def.key, detail, problem.first, problem.second)
            }
        }

        val sounds = listOf(
            Triple(RingtoneManager.TYPE_RINGTONE, snapshot.ringtone, SelectionCatalog.SETTING_RINGTONE),
            Triple(RingtoneManager.TYPE_NOTIFICATION, snapshot.notificationSound, SelectionCatalog.SETTING_NOTIFICATION),
            Triple(RingtoneManager.TYPE_ALARM, snapshot.alarmSound, SelectionCatalog.SETTING_ALARM)
        ).filter { it.second != null && wanted(it.third) }
        for ((type, ref, id) in sounds) {
            val title = ref!!.title.ifBlank { ref.displayName }
            when {
                !canWrite && !shizuku -> issue(id, title, id, IssueReason.NEEDS_WRITE_SETTINGS)
                SoundRefResolver.applyDefault(context, type, ref) -> applied++

                else -> issue(id, title, id, IssueReason.SOUND_MISSING)
            }
        }

        if (wallpaperBytes != null && wallpaperBytes.isNotEmpty() && wanted(SelectionCatalog.SETTING_WALLPAPER)) {
            runCatching {
                val wm = WallpaperManager.getInstance(context)
                ByteArrayInputStream(wallpaperBytes).use { input ->
                    wm.setStream(input)
                }
                applied++
            }.onFailure {
                issue(
                    SelectionCatalog.SETTING_WALLPAPER, "wallpaper", "wallpaper.png",
                    IssueReason.REFUSED_BY_SYSTEM, it.message.orEmpty(), retryable = false
                )
            }
        }

        if (snapshot.personalDictionaryWords.isNotEmpty() && onlyIds == null) {
            runCatching {
                for (word in snapshot.personalDictionaryWords) {
                    val cv = ContentValues().apply {
                        put(UserDictionary.Words.WORD, word)
                        put(UserDictionary.Words.FREQUENCY, 250)
                        put(UserDictionary.Words.APP_ID, 0)
                    }
                    cr.insert(UserDictionary.Words.CONTENT_URI, cv)
                }
                applied += snapshot.personalDictionaryWords.size
            }
        }

        val stat = CategoryImportStat(
            category = ExportCategory.SYSTEM_SETTINGS,
            importedCount = applied,
            failedCount = issues.size
        )
        return stat to issues
    }

    private fun extractWallpaperPng(context: Context): ByteArray? {
        return runCatching {
            val wm = WallpaperManager.getInstance(context)
            val drawable = wm.drawable ?: return@runCatching null
            var createdTemp: Bitmap? = null
            val sourceBmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val w = drawable.intrinsicWidth.coerceIn(1, 1440)
                val h = drawable.intrinsicHeight.coerceIn(1, 1440)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    createdTemp = b
                    val canvas = Canvas(b)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
            var scaledTemp: Bitmap? = null
            val targetBmp = if (sourceBmp.width > 1440 || sourceBmp.height > 1440) {
                val ratio = 1440f / maxOf(sourceBmp.width, sourceBmp.height).toFloat()
                val nw = (sourceBmp.width * ratio).toInt().coerceAtLeast(1)
                val nh = (sourceBmp.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(sourceBmp, nw, nh, true).also { scaledTemp = it }
            } else {
                sourceBmp
            }
            try {
                val out = ByteArrayOutputStream()
                targetBmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                out.toByteArray()
            } finally {
                scaledTemp?.recycle()
                createdTemp?.recycle()
            }
        }.getOrNull()
    }
}
