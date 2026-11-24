package org.etg.migration.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExportCategory {
    CONTACTS,
    MESSAGES_SMS_MMS,
    CALL_LOG,
    CHAT_BACKUPS,
    CALENDAR,
    PHOTOS_VIDEOS,
    AUDIO_RINGTONES,
    DOCUMENTS_DOWNLOADS,
    VAULT_KEYS_2FA,
    WIFI_NETWORKS,
    APPS_AND_APKS,
    SYSTEM_SETTINGS
}

@Serializable
enum class CategoryStatus {
    READY,
    NEEDS_PERMISSION,
    GUIDED,
    EMPTY
}

@Serializable
enum class TransferMode {
    LOCAL_FILE,
    USB_DRIVE,
    WIRED_CABLE,
    WIRELESS
}

@Serializable
enum class WifiSecurityType {
    WPA2,
    WPA3,
    WEP,
    OPEN
}

@Serializable
enum class ReadinessSeverity {
    CRITICAL,
    WARN,
    INFO
}

@Serializable
enum class OperationPhase {
    IDLE,
    SCANNING,
    EXPORTING,
    TRANSFERRING,
    INSPECTING,
    IMPORTING,
    COMPLETED,
    ERROR,
    CANCELLED
}

@Serializable
data class CategoryScanInfo(
    val category: ExportCategory,
    val itemCount: Int = 0,
    val estimatedBytes: Long = 0L,
    val status: CategoryStatus = CategoryStatus.EMPTY,
    val detailSummary: String = "",
    val missingPermissions: List<String> = emptyList(),
    val requiresAllFilesAccess: Boolean = false
)

@Serializable
data class WifiNetworkEntry(
    val ssid: String,
    val password: String = "",
    val securityType: WifiSecurityType = WifiSecurityType.WPA2,
    val hidden: Boolean = false,
    val source: String = "manual"
)

@Serializable
data class ChatAppInfo(
    val packageName: String,
    val displayName: String,
    val installed: Boolean,
    val backupPaths: List<String> = emptyList(),
    val detectedFilesCount: Int = 0,
    val detectedBytes: Long = 0L,
    val instructions: String = ""
)

@Serializable
data class AppPackageEntry(
    val packageName: String,
    val label: String,
    val versionName: String = "",
    val versionCode: Long = 0L,
    val installerPackage: String? = null,
    val totalBytes: Long = 0L,
    val apkRelativePaths: List<String> = emptyList(),
    val isBankingOrPlayDependent: Boolean = false,
    val openSourceAlternatives: String = ""
)

@Serializable
data class BluetoothDeviceEntry(
    val name: String,
    val address: String,
    val deviceClass: Int = 0
)

@Serializable
data class SystemAuditItem(
    val key: String,
    val label: String,
    val value: String,
    val settingsAction: String = "",
    val categoryHint: String = ""
)

@Serializable
data class SoundRef(
    val title: String = "",
    val displayName: String = "",
    val silent: Boolean = false
)

@Serializable
data class SystemSettingsSnapshot(
    val writableSystemSettings: Map<String, String> = emptyMap(),
    val systemAuditItems: List<SystemAuditItem> = emptyList(),
    val personalDictionaryWords: List<String> = emptyList(),
    val pairedBluetooth: List<BluetoothDeviceEntry> = emptyList(),
    val hasWallpaper: Boolean = false,
    val userLocale: String = "",
    val privateDnsMode: String = "",
    val privateDnsSpecifier: String = "",
    val ringtone: SoundRef? = null,
    val notificationSound: SoundRef? = null,
    val alarmSound: SoundRef? = null,

    val extendedSettings: Map<String, String> = emptyMap()
)

@Serializable
data class GrapheneReadinessItem(
    val id: String,
    val title: String,
    val severity: ReadinessSeverity,
    val description: String,
    val relatedPackages: List<String> = emptyList()
)

@Serializable
data class ContactPhone(
    val number: String,
    val type: Int = 2,
    val label: String? = null
)

@Serializable
data class ContactEmail(
    val address: String,
    val type: Int = 1,
    val label: String? = null
)

@Serializable
data class ContactAddress(
    val formatted: String = "",
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postalCode: String = "",
    val country: String = "",
    val type: Int = 1,
    val label: String? = null
)

@Serializable
data class ContactEvent(
    val startDate: String,
    val type: Int = 3,
    val label: String? = null
)

@Serializable
data class ContactRecord(
    val id: Long = 0L,
    val displayName: String = "",
    val givenName: String = "",
    val familyName: String = "",
    val middleName: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val organization: String = "",
    val jobTitle: String = "",
    val note: String = "",
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<ContactEmail> = emptyList(),
    val addresses: List<ContactAddress> = emptyList(),
    val events: List<ContactEvent> = emptyList(),
    val websites: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val photoPath: String? = null,
    val photoBase64: String? = null,
    val starred: Boolean = false,
    val sendToVoicemail: Boolean = false,
    val customRingtone: SoundRef? = null
)

@Serializable
data class CalendarReminderRecord(
    val minutes: Int,
    val method: Int = 1
)

@Serializable
data class CalendarEventRecord(
    val uid: String,
    val calendarName: String = "ETG Local Calendar",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val dtStartMs: Long,
    val dtEndMs: Long,
    val allDay: Boolean = false,
    val timezone: String = "UTC",
    val rrule: String? = null,
    val rdate: String? = null,
    val exdate: String? = null,
    val duration: String? = null,
    val reminders: List<CalendarReminderRecord> = emptyList()
)

@Serializable
data class CalendarRecord(
    val id: Long,
    val name: String,
    val displayName: String,
    val accountName: String = "",
    val accountType: String = "",
    val color: Int = 0,
    val timeZone: String = "UTC",
    val events: List<CalendarEventRecord> = emptyList()
)

@Serializable
data class SmsRecord(
    val address: String,
    val body: String,
    val dateMs: Long,
    val dateSentMs: Long = 0L,
    val type: Int = 1,
    val read: Int = 1,
    val seen: Int = 1,
    val threadId: Long = 0L,
    val subject: String? = null
)

@Serializable
data class MmsAddressRecord(
    val address: String,
    val type: Int
)

@Serializable
data class MmsPartRecord(
    val contentType: String,
    val text: String? = null,
    val attachmentArchivePath: String? = null,
    val fileName: String? = null
)

@Serializable
data class MmsRecord(
    val id: Long,
    val dateSec: Long,
    val msgBox: Int,
    val read: Int = 1,
    val subject: String? = null,
    val addresses: List<MmsAddressRecord> = emptyList(),
    val parts: List<MmsPartRecord> = emptyList()
)

@Serializable
data class SmsMmsBackup(
    val sms: List<SmsRecord> = emptyList(),
    val mms: List<MmsRecord> = emptyList()
)

@Serializable
data class BlockedNumberRecord(
    val number: String
)

@Serializable
data class OtpAccount(
    val name: String,
    val issuer: String = "",
    val secretBase32: String,
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val type: String = "totp",
    val counter: Long = 0L,
    val period: Int = 30
)

@Serializable
data class CallLogRecord(
    val number: String,
    val cachedName: String? = null,
    val dateMs: Long,
    val durationSec: Long,
    val type: Int
)

@Serializable
data class MediaFileEntry(
    val category: ExportCategory,
    val archivePath: String,
    val relativePath: String,
    val fileName: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val dateModifiedSec: Long = 0L,
    val dateTakenMs: Long = 0L
)

@Serializable
data class ArchiveEntryMeta(
    val path: String,
    val category: ExportCategory,
    val sizeBytes: Long,
    val sha256: String
)

@Serializable
data class ArchiveManifest(
    val version: Int = 1,
    val createdAtIso: String,
    val sourceDeviceModel: String,
    val sourceManufacturer: String,
    val sourceAndroidVersion: String,
    val sourceSdkInt: Int,
    val encrypted: Boolean,
    val categories: List<ExportCategory>,
    val entries: List<ArchiveEntryMeta> = emptyList(),
    val mediaIndex: List<MediaFileEntry> = emptyList(),
    val grapheneAuditNotes: List<GrapheneReadinessItem> = emptyList(),
    val summaryCounts: Map<String, Int> = emptyMap(),

    val failedEntries: List<String> = emptyList(),

    val entryAliases: Map<String, String> = emptyMap(),

    val exportIssues: List<TransferIssue> = emptyList()
)

enum class IssuePhase { EXPORT, IMPORT }

enum class IssueReason {
    NOT_FOUND,
    NO_PERMISSION,
    IO_ERROR,
    CORRUPTED,
    READ_INTERRUPTED,
    WRITE_FAILED,
    APP_NOT_INSTALLED,
    NEEDS_SHIZUKU,
    NEEDS_WRITE_SETTINGS,
    SOUND_MISSING,
    REFUSED_BY_SYSTEM,
    PARTIAL_IMPORT,
    UNKNOWN
}

@Serializable
data class TransferIssue(
    val id: String = java.util.UUID.randomUUID().toString(),
    val phase: IssuePhase,

    val category: ExportCategory?,

    val itemName: String,

    val detail: String = "",
    val reason: IssueReason,
    val reasonDetail: String = "",
    val retryable: Boolean = true,

    val categoryTotal: Int = 0,

    val archivePath: String = "",

    val settingId: String = "",
    val attempts: Int = 0
)

@Serializable
data class ExportConfig(
    val selectedCategories: Set<ExportCategory> = ExportCategory.entries.toSet(),
    val includeVideos: Boolean = true,
    val includeApks: Boolean = true,
    val encryptArchive: Boolean = true,
    val encryptionPassword: String = "",
    val destinationMode: TransferMode = TransferMode.LOCAL_FILE,
    val usbUnpackedFolderMode: Boolean = false,
    val extraSafFolders: List<String> = emptyList(),
    val manualWifiEntries: List<WifiNetworkEntry> = emptyList(),
    val authenticatorAccounts: List<OtpAccount> = emptyList(),

    val exclusions: Map<ExportCategory, Set<String>> = emptyMap(),

    val pickedFiles: List<PickedFile> = emptyList()
) {
    fun excluded(category: ExportCategory): Set<String> = exclusions[category].orEmpty()
}

@Serializable
data class PickedFile(
    val uri: String,
    val name: String,
    val category: ExportCategory,
    val sizeBytes: Long = 0L,

    val label: String = ""
)

@Serializable
data class OperationProgress(
    val phase: OperationPhase = OperationPhase.IDLE,
    val currentCategory: ExportCategory? = null,
    val currentItemLabel: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val errorMessage: String? = null
)

@Serializable
data class CategoryImportStat(
    val category: ExportCategory,
    val importedCount: Int = 0,
    val skippedDuplicates: Int = 0,
    val failedCount: Int = 0,
    val notes: String = ""
)

@Serializable
data class ImportPreview(
    val sourceLabel: String,
    val isEncrypted: Boolean = false,
    val isUnlocked: Boolean = false,
    val manifest: ArchiveManifest? = null,
    val archiveEntriesCount: Int = 0,
    val totalUncompressedBytes: Long = 0L,
    val wifiNetworks: List<WifiNetworkEntry> = emptyList(),
    val apps: List<AppPackageEntry> = emptyList(),
    val settingsSnapshot: SystemSettingsSnapshot? = null,
    val grapheneAuditNotes: List<GrapheneReadinessItem> = emptyList(),
    val availableCategories: Set<ExportCategory> = emptySet(),
    val errorMessage: String? = null
)

@Serializable
data class ImportResult(
    val categoryStats: List<CategoryImportStat> = emptyList(),
    val wifiNetworksToSave: List<WifiNetworkEntry> = emptyList(),
    val needsSmsRoleForRestore: Boolean = false,
    val restoredSmsPendingCount: Int = 0,
    val extractedApkEntries: List<AppPackageEntry> = emptyList(),
    val settingsSnapshot: SystemSettingsSnapshot? = null,
    val completedAtIso: String = "",
    val issues: List<TransferIssue> = emptyList()
)

@Serializable
data class StorageVolumeInfo(
    val id: String,
    val description: String,
    val path: String = "",
    val isRemovableUsb: Boolean = false,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val treeUriString: String? = null
)
