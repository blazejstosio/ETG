package org.etg.migration.ui

import org.etg.migration.data.integration.SinusChatBridge
import org.etg.migration.data.archive.IssueLog
import org.etg.migration.model.IssuePhase
import org.etg.migration.model.IssueReason
import org.etg.migration.model.TransferIssue
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.etg.migration.codec.WifiQrAndXmlParser
import org.etg.migration.data.PermissionHelper
import org.etg.migration.data.archive.ArchiveSource
import org.etg.migration.data.archive.EtgArchiveReader
import org.etg.migration.data.archive.ExportOrchestrator
import org.etg.migration.data.archive.UsbFolderArchiveSink
import org.etg.migration.data.archive.ZipArchiveSink
import org.etg.migration.data.exporter.AppsExporter
import org.etg.migration.data.exporter.CalendarExporter
import org.etg.migration.data.exporter.ContactsExporter
import org.etg.migration.data.exporter.MediaAndFilesExporter
import org.etg.migration.data.exporter.MessagesAndCallsExporter
import org.etg.migration.data.exporter.SettingsExporter
import org.etg.migration.data.exporter.WifiExporter
import org.etg.migration.data.storage.StorageInspector
import org.etg.migration.data.exporter.SelectableItem
import org.etg.migration.data.exporter.SelectionCatalog
import org.etg.migration.data.shizuku.ShizukuBridge
import org.etg.migration.model.PickedFile
import org.etg.migration.data.transfer.ClientAuth
import org.etg.migration.data.transfer.DetectedNetInterface
import org.etg.migration.data.transfer.DiscoveredPeer
import org.etg.migration.data.transfer.LocalWebDownloadServer
import org.etg.migration.data.transfer.NetworkInterfaceInspector
import org.etg.migration.data.transfer.NfcPairing
import org.etg.migration.data.transfer.NsdPeerDiscovery
import org.etg.migration.data.transfer.PeerRequest
import org.etg.migration.data.transfer.TransferAuthException
import org.etg.migration.data.transfer.TransferHeaderInfo
import org.etg.migration.data.transfer.TransferProtocol
import org.etg.migration.data.transfer.TransferRejectedException
import org.etg.migration.data.transfer.WifiDirectController
import org.etg.migration.data.transfer.WifiDirectState
import org.etg.migration.model.AppPackageEntry
import org.etg.migration.model.CategoryScanInfo
import org.etg.migration.model.ChatAppInfo
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.ExportConfig
import org.etg.migration.model.GrapheneReadinessItem
import org.etg.migration.model.ImportPreview
import org.etg.migration.model.ImportResult
import org.etg.migration.model.OperationPhase
import org.etg.migration.model.OperationProgress
import org.etg.migration.model.StorageVolumeInfo
import org.etg.migration.model.TransferMode
import org.etg.migration.model.WifiNetworkEntry
import org.etg.migration.model.OtpAccount
import org.etg.migration.R
import org.etg.migration.codec.OtpMigrationCodec
import org.etg.migration.ui.theme.createLocalizedContext
import org.etg.migration.service.TransferForegroundService
import org.etg.migration.ui.theme.EtgLanguagePreference
import org.etg.migration.ui.theme.EtgThemePreference
import java.io.File
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class EtgTab {
    EXPORT,
    IMPORT,
    TRANSFER,
    GUIDE
}

enum class TransferRole {
    SEND,
    RECEIVE
}

enum class TransferLinkMode {
    WIRELESS,
    WIRED
}

enum class SinusPhase {
    IDLE,
    WAITING,
    RECEIVING
}

enum class ReceiverPhase {
    IDLE,
    CONNECTING,
    AWAITING_APPROVAL,
    RECEIVING
}

data class IssueSession(
    val phase: IssuePhase,
    val pending: List<TransferIssue>,

    val all: List<TransferIssue>,
    val skipped: List<TransferIssue> = emptyList(),
    val fixed: Int = 0,
    val busy: Boolean = false,

    val finished: Boolean = false
)

data class EtgUiState(
    val selectedTab: EtgTab = EtgTab.EXPORT,
    val themePref: EtgThemePreference = EtgThemePreference.DARK,
    val langPref: EtgLanguagePreference = EtgLanguagePreference.SYSTEM,
    val isScanning: Boolean = false,
    val scanInfos: Map<ExportCategory, CategoryScanInfo> = emptyMap(),
    val chatApps: List<ChatAppInfo> = emptyList(),
    val installedApps: List<AppPackageEntry> = emptyList(),
    val readinessItems: List<GrapheneReadinessItem> = emptyList(),
    val usbVolumes: List<StorageVolumeInfo> = emptyList(),
    val exportConfig: ExportConfig = ExportConfig(),
    val progress: OperationProgress = OperationProgress(),
    val statusBannerMessage: String? = null,
    val sinusPhase: SinusPhase = SinusPhase.IDLE,
    val sinusProgress: Float? = null,
    val sinusWithMedia: Boolean = false,
    val sinusReceivedAt: Long = 0L,
    val importSource: ArchiveSource? = null,
    val importPreview: ImportPreview? = null,
    val importSelectedCategories: Set<ExportCategory> = emptySet(),
    val importPassword: String = "",
    val importResult: ImportResult? = null,
    val issueSession: IssueSession? = null,
    val transferRole: TransferRole = TransferRole.SEND,
    val transferLinkMode: TransferLinkMode = TransferLinkMode.WIRELESS,
    val transferPort: Int = TransferProtocol.DEFAULT_PORT,
    val senderRunning: Boolean = false,
    val senderPin: String = TransferProtocol.generatePin(),
    val senderStatusMessage: String = "",
    val webServerEnabled: Boolean = false,
    val webServerRunning: Boolean = false,
    val webServerPort: Int = LocalWebDownloadServer.DEFAULT_WEB_PORT,
    val netInterfaces: List<DetectedNetInterface> = emptyList(),
    val discoveredPeers: List<DiscoveredPeer> = emptyList(),
    val wifiDirectState: WifiDirectState = WifiDirectState(),
    val receiveHost: String = "",
    val receivePort: String = TransferProtocol.DEFAULT_PORT.toString(),
    val receivePin: String = "",
    val receiveCode: String = "",
    val receiverPhase: ReceiverPhase = ReceiverPhase.IDLE,
    val receiverVerificationCode: String = "",
    val receiverPeerName: String = "",
    val senderShortCode: String = "",
    val senderPairingUri: String = "",
    val pendingPeerRequest: PeerRequest? = null,
    val shizukuStatus: ShizukuBridge.Status = ShizukuBridge.Status.NOT_INSTALLED,
    val shizukuBusy: Boolean = false,
    val catalogs: Map<ExportCategory, List<SelectableItem>> = emptyMap(),
    val catalogLoading: Set<ExportCategory> = emptySet(),
    val checklistCompletedIds: Set<String> = emptySet(),
    val hasAllFilesAccess: Boolean = false,
    val isDefaultSmsApp: Boolean = false,
    val canWriteSettings: Boolean = false,
    val canInstallApks: Boolean = false
)

class EtgViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Context = application.applicationContext
    private val prefs = appContext.getSharedPreferences("etg_prefs", Context.MODE_PRIVATE)
    private val nsdDiscovery = NsdPeerDiscovery(appContext)
    private val wifiDirect = WifiDirectController(appContext)

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<EtgUiState> = _uiState.asStateFlow()

    private val operationCancelled = AtomicBoolean(false)
    private var activeJob: Job? = null

    private var exportIssueLog: IssueLog? = null
    private var exportIssuesDone: CompletableDeferred<Unit>? = null
    private var senderServerJob: Job? = null
    private var activeServerSocket: ServerSocket? = null
    private var webServer: LocalWebDownloadServer? = null
    private var webTempArchiveFile: File? = null
    private var lastPermissionSignature: String = ""
    @Volatile
    private var pendingPeerDecision: CompletableDeferred<Boolean>? = null
    private var receiveJob: Job? = null

    init {
        viewModelScope.launch {
            nsdDiscovery.peers.collect { peers ->
                _uiState.update { it.copy(discoveredPeers = peers) }
            }
        }
        viewModelScope.launch {
            wifiDirect.state.collect { p2p ->
                _uiState.update { state ->
                    val nextHost = if (!p2p.isGroupOwner && !p2p.groupOwnerIp.isNullOrBlank() && state.receiveHost.isBlank()) {
                        p2p.groupOwnerIp
                    } else state.receiveHost
                    state.copy(wifiDirectState = p2p, receiveHost = nextHost)
                }
            }
        }
        refreshAllScans()
        refreshNetworkInterfaces()
    }

    private fun loadInitialState(): EtgUiState {
        val themeStr = prefs.getString("theme_pref", EtgThemePreference.DARK.name)
        val langStr = prefs.getString("lang_pref", EtgLanguagePreference.SYSTEM.name)
        val port = prefs.getInt("transfer_port", TransferProtocol.DEFAULT_PORT)
        val checklist = prefs.getStringSet("checklist_done", emptySet()).orEmpty()
        return EtgUiState(
            themePref = runCatching { EtgThemePreference.valueOf(themeStr!!) }.getOrDefault(EtgThemePreference.DARK),
            langPref = runCatching { EtgLanguagePreference.valueOf(langStr!!) }.getOrDefault(EtgLanguagePreference.SYSTEM),
            transferPort = port,
            receivePort = port.toString(),
            checklistCompletedIds = checklist.toSet()
        )
    }

    fun selectTab(tab: EtgTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == EtgTab.TRANSFER) {
            refreshNetworkInterfaces()
            if (_uiState.value.transferRole == TransferRole.RECEIVE) {
                nsdDiscovery.startDiscovery()
            }
        }
    }

    fun setThemePref(pref: EtgThemePreference) {
        prefs.edit().putString("theme_pref", pref.name).apply()
        _uiState.update { it.copy(themePref = pref) }
    }

    fun setLangPref(pref: EtgLanguagePreference) {
        prefs.edit().putString("lang_pref", pref.name).apply()
        _uiState.update { it.copy(langPref = pref) }
        refreshAllScans()
    }

    fun setTransferPort(port: Int) {
        val valid = port.coerceIn(1024, 65535)
        prefs.edit().putInt("transfer_port", valid).apply()
        _uiState.update { it.copy(transferPort = valid, receivePort = valid.toString()) }
    }

    fun toggleChecklistItem(id: String) {
        _uiState.update { state ->
            val next = if (id in state.checklistCompletedIds) {
                state.checklistCompletedIds - id
            } else {
                state.checklistCompletedIds + id
            }
            prefs.edit().putStringSet("checklist_done", next).apply()
            state.copy(checklistCompletedIds = next)
        }
    }

    fun clearBannerMessage() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    fun onResumeRefresh() {
        refreshSystemCapabilities()
        refreshShizukuStatus()
        val sig = buildPermissionSignature()
        if (sig != lastPermissionSignature || _uiState.value.scanInfos.isEmpty()) {
            refreshAllScans()
        }
    }

    private fun buildPermissionSignature(): String = listOf(
        PermissionHelper.hasContactsRead(appContext),
        PermissionHelper.hasCalendarRead(appContext),
        PermissionHelper.hasSmsRead(appContext),
        PermissionHelper.hasCallLogRead(appContext),
        PermissionHelper.hasImagesRead(appContext),
        PermissionHelper.hasVideosRead(appContext),
        PermissionHelper.hasAudioRead(appContext),
        PermissionHelper.hasAllFilesAccess(appContext),
        PermissionHelper.hasBluetoothConnect(appContext),
        PermissionHelper.isDefaultSmsApp(appContext)
    ).joinToString(",")

    fun refreshSystemCapabilities() {
        _uiState.update {
            it.copy(
                hasAllFilesAccess = PermissionHelper.hasAllFilesAccess(appContext),
                isDefaultSmsApp = PermissionHelper.isDefaultSmsApp(appContext),
                canWriteSettings = PermissionHelper.canWriteSystemSettings(appContext),
                canInstallApks = PermissionHelper.canInstallPackages(appContext),
                usbVolumes = StorageInspector.listRemovableUsbVolumes(appContext)
            )
        }
    }

    fun refreshNetworkInterfaces() {
        val ifaces = NetworkInterfaceInspector.inspectInterfaces()
        _uiState.update { it.copy(netInterfaces = ifaces) }
    }

    fun refreshAllScans() {
        lastPermissionSignature = buildPermissionSignature()
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            val cfg = _uiState.value.exportConfig

            val ctx = createLocalizedContext(appContext, _uiState.value.langPref)
            val result = withContext(Dispatchers.IO) {
                val map = linkedMapOf<ExportCategory, CategoryScanInfo>()
                map[ExportCategory.CONTACTS] = ContactsExporter.scan(ctx)
                map[ExportCategory.MESSAGES_SMS_MMS] = MessagesAndCallsExporter.scanSmsMms(ctx)
                map[ExportCategory.CALL_LOG] = MessagesAndCallsExporter.scanCallLog(ctx)
                val (chatScan, chatApps) = MediaAndFilesExporter.inspectChatApps(ctx, cfg.extraSafFolders)
                map[ExportCategory.CHAT_BACKUPS] = chatScan
                map[ExportCategory.CALENDAR] = CalendarExporter.scan(ctx)
                map[ExportCategory.PHOTOS_VIDEOS] = MediaAndFilesExporter.scanPhotosVideos(ctx, cfg.includeVideos)
                map[ExportCategory.AUDIO_RINGTONES] = MediaAndFilesExporter.scanAudio(ctx)
                map[ExportCategory.DOCUMENTS_DOWNLOADS] = MediaAndFilesExporter.scanDocumentsDownloads(ctx, cfg.extraSafFolders)
                map[ExportCategory.VAULT_KEYS_2FA] = MediaAndFilesExporter.scanVaultKeys(ctx, cfg.extraSafFolders)
                map[ExportCategory.WIFI_NETWORKS] = WifiExporter.scan(ctx, cfg.manualWifiEntries)
                val (appsScan, appsList) = AppsExporter.scan(ctx, cfg.includeApks)
                map[ExportCategory.APPS_AND_APKS] = appsScan
                map[ExportCategory.SYSTEM_SETTINGS] = SettingsExporter.scan(ctx)
                val audit = SettingsExporter.buildGrapheneReadinessAudit(ctx, appsList)
                val usbs = StorageInspector.listRemovableUsbVolumes(ctx)
                ScanBundle(map, chatApps, appsList, audit, usbs)
            }
            _uiState.update {
                it.copy(
                    isScanning = false,
                    scanInfos = result.scans,
                    chatApps = result.chatApps,
                    installedApps = result.apps,
                    readinessItems = result.audit,
                    usbVolumes = result.usbs,
                    hasAllFilesAccess = PermissionHelper.hasAllFilesAccess(appContext),
                    isDefaultSmsApp = PermissionHelper.isDefaultSmsApp(appContext),
                    canWriteSettings = PermissionHelper.canWriteSystemSettings(appContext),
                    canInstallApks = PermissionHelper.canInstallPackages(appContext)
                )
            }
        }
    }

    private data class ScanBundle(
        val scans: Map<ExportCategory, CategoryScanInfo>,
        val chatApps: List<ChatAppInfo>,
        val apps: List<AppPackageEntry>,
        val audit: List<GrapheneReadinessItem>,
        val usbs: List<StorageVolumeInfo>
    )

    fun toggleCategory(cat: ExportCategory) {
        _uiState.update { state ->
            val current = state.exportConfig.selectedCategories
            val next = if (cat in current) current - cat else current + cat
            state.copy(exportConfig = state.exportConfig.copy(selectedCategories = next))
        }
    }

    fun selectAllCategories() {
        _uiState.update {
            it.copy(exportConfig = it.exportConfig.copy(selectedCategories = ExportCategory.entries.toSet()))
        }
    }

    fun clearAllCategories() {
        _uiState.update {
            it.copy(exportConfig = it.exportConfig.copy(selectedCategories = emptySet()))
        }
    }

    fun setIncludeVideos(enabled: Boolean) {
        _uiState.update { it.copy(exportConfig = it.exportConfig.copy(includeVideos = enabled)) }
        refreshAllScans()
    }

    fun setIncludeApks(enabled: Boolean) {
        _uiState.update { it.copy(exportConfig = it.exportConfig.copy(includeApks = enabled)) }
        refreshAllScans()
    }

    fun setUsbUnpackedFolderMode(enabled: Boolean) {
        _uiState.update { it.copy(exportConfig = it.exportConfig.copy(usbUnpackedFolderMode = enabled)) }
    }

    fun setEncryptArchive(enabled: Boolean) {
        _uiState.update { it.copy(exportConfig = it.exportConfig.copy(encryptArchive = enabled)) }
    }

    fun setEncryptionPassword(pw: String) {
        _uiState.update { it.copy(exportConfig = it.exportConfig.copy(encryptionPassword = pw)) }
    }

    fun addExtraSafFolder(treeUri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val str = treeUri.toString()
        _uiState.update { state ->
            val updated = (state.exportConfig.extraSafFolders + str).distinct()
            state.copy(exportConfig = state.exportConfig.copy(extraSafFolders = updated))
        }
        refreshAllScans()
    }

    fun removeExtraSafFolder(uriStr: String) {
        _uiState.update { state ->
            val updated = state.exportConfig.extraSafFolders.filterNot { it == uriStr }
            state.copy(exportConfig = state.exportConfig.copy(extraSafFolders = updated))
        }
        refreshAllScans()
    }

    fun addWifiFromQr(rawQr: String): Boolean {
        val entry = WifiQrAndXmlParser.parseWifiQr(rawQr) ?: return false
        addManualWifi(entry)
        return true
    }

    fun addManualWifi(entry: WifiNetworkEntry) {
        _uiState.update { state ->
            val existing = state.exportConfig.manualWifiEntries
                .filterNot { it.ssid == entry.ssid && it.securityType == entry.securityType }
            val next = existing + entry
            val updatedWifiScan = WifiExporter.scan(appContext, next)
            state.copy(
                exportConfig = state.exportConfig.copy(manualWifiEntries = next),
                scanInfos = state.scanInfos + (ExportCategory.WIFI_NETWORKS to updatedWifiScan)
            )
        }
    }

    fun addAuthenticatorFromQr(rawQr: String): Boolean {
        val batch = OtpMigrationCodec.parse(rawQr) ?: return false
        val res = createLocalizedContext(appContext, _uiState.value.langPref)
        _uiState.update { state ->
            val merged = OtpMigrationCodec.merge(state.exportConfig.authenticatorAccounts, batch.accounts)
            val added = merged.size - state.exportConfig.authenticatorAccounts.size
            state.copy(
                exportConfig = state.exportConfig.copy(authenticatorAccounts = merged),
                statusBannerMessage = res.getString(
                    R.string.otp_scan_result,
                    added,
                    batch.batchIndex + 1,
                    batch.batchSize
                )
            )
        }
        return true
    }

    fun removeAuthenticatorAccount(account: OtpAccount) {
        _uiState.update { state ->
            state.copy(
                exportConfig = state.exportConfig.copy(
                    authenticatorAccounts = state.exportConfig.authenticatorAccounts - account
                )
            )
        }
    }

    fun removeWifi(entry: WifiNetworkEntry) {
        _uiState.update { state ->
            val next = state.exportConfig.manualWifiEntries
                .filterNot { it.ssid == entry.ssid && it.securityType == entry.securityType }
            val updatedWifiScan = WifiExporter.scan(appContext, next)
            state.copy(
                exportConfig = state.exportConfig.copy(manualWifiEntries = next),
                scanInfos = state.scanInfos + (ExportCategory.WIFI_NETWORKS to updatedWifiScan)
            )
        }
    }

    fun importWifiConfigXmlUri(uri: Uri) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val text = appContext.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText().orEmpty()
                    WifiQrAndXmlParser.parseWifiConfigStoreXml(text)
                }.getOrDefault(emptyList())
            }
            if (imported.isEmpty()) {
                _uiState.update { it.copy(statusBannerMessage = "No Wi-Fi networks parsed from XML") }
            } else {
                imported.forEach { addManualWifi(it) }
                _uiState.update { it.copy(statusBannerMessage = "Imported ${imported.size} Wi-Fi networks from XML") }
            }
        }
    }

    fun loadCatalog(category: ExportCategory, force: Boolean = false) {
        val state = _uiState.value
        if (!force && (category in state.catalogs || category in state.catalogLoading)) return
        _uiState.update { it.copy(catalogLoading = it.catalogLoading + category) }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                val cfg = _uiState.value.exportConfig
                runCatching { SelectionCatalog.load(appContext, category, cfg.includeVideos, cfg.extraSafFolders) }
                    .getOrDefault(emptyList())
            }
            _uiState.update {
                it.copy(catalogs = it.catalogs + (category to items), catalogLoading = it.catalogLoading - category)
            }
        }
    }

    fun setItemIncluded(category: ExportCategory, key: String, included: Boolean) {
        _uiState.update { state ->
            val current = state.exportConfig.excluded(category)
            val next = if (included) current - key else current + key
            state.copy(exportConfig = state.exportConfig.copy(exclusions = state.exportConfig.exclusions + (category to next)))
        }
    }

    fun setAllItemsIncluded(category: ExportCategory, keys: Collection<String>, included: Boolean) {
        _uiState.update { state ->
            val current = state.exportConfig.excluded(category)
            val next = if (included) current - keys.toSet() else current + keys
            state.copy(exportConfig = state.exportConfig.copy(exclusions = state.exportConfig.exclusions + (category to next)))
        }
    }

    fun addPickedFiles(uris: List<Uri>, category: ExportCategory) {
        val added = uris.mapNotNull { uri ->
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            var name = "backup"
            var size = 0L
            runCatching {
                appContext.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        size = if (c.isNull(1)) 0L else c.getLong(1)
                    }
                }
            }
            val lower = name.lowercase()
            val label = when {
                lower.endsWith(".sinuschat") -> "SinusChat"
                lower.contains("2fas") -> "2FAS"
                else -> ""
            }
            PickedFile(uri.toString(), name, category, size, label)
        }
        if (added.isEmpty()) return
        _uiState.update { state ->
            val merged = (state.exportConfig.pickedFiles + added).distinctBy { it.uri }
            state.copy(exportConfig = state.exportConfig.copy(pickedFiles = merged))
        }
    }

    fun sinusChatExportStarted(started: Boolean) {
        _uiState.update { it.copy(sinusPhase = if (started) SinusPhase.WAITING else SinusPhase.IDLE, sinusProgress = null) }
    }

    fun receiveSinusChatExport(resultCode: Int, data: Intent?) {
        if (resultCode == android.app.Activity.RESULT_OK) {
            _uiState.update { it.copy(sinusPhase = SinusPhase.RECEIVING, sinusProgress = null) }
        }
        viewModelScope.launch {
            var lastShown = -1f
            val outcome = withContext(Dispatchers.IO) {
                SinusChatBridge.receiveExport(appContext, resultCode, data) { f ->
                    if (f - lastShown >= 0.02f || f >= 1f) {
                        lastShown = f
                        _uiState.update { it.copy(sinusProgress = f) }
                    }
                }
            }
            _uiState.update { it.copy(sinusPhase = SinusPhase.IDLE, sinusProgress = null) }
            val res = createLocalizedContext(appContext, _uiState.value.langPref)
            when (outcome) {
                is SinusChatBridge.ExportOutcome.Ready -> {
                    val picked = PickedFile(
                        uri = Uri.fromFile(outcome.file).toString(),
                        name = outcome.file.name,
                        category = ExportCategory.CHAT_BACKUPS,
                        sizeBytes = outcome.file.length(),
                        label = "SinusChat"
                    )
                    _uiState.update { state ->

                        val kept = state.exportConfig.pickedFiles.filterNot { SinusChatBridge.isReceivedExport(appContext, it.uri) }
                        state.copy(
                            exportConfig = state.exportConfig.copy(pickedFiles = kept + picked),
                            sinusWithMedia = outcome.withMedia,
                            sinusReceivedAt = System.currentTimeMillis(),
                            statusBannerMessage = res.getString(R.string.sinus_export_ready)
                        )
                    }
                }
                is SinusChatBridge.ExportOutcome.Failed -> {
                    val msg = when (outcome.code) {
                        "cancelled" -> null
                        "not_ready" -> R.string.sinus_err_not_ready
                        "no_recovery_key" -> R.string.sinus_err_no_key
                        "no_caller" -> R.string.sinus_err_no_caller
                        "copy_failed" -> R.string.sinus_err_copy
                        else -> R.string.sinus_err_failed
                    }
                    if (msg != null) _uiState.update { it.copy(statusBannerMessage = res.getString(msg)) }
                }
            }
        }
    }

    fun removePickedFile(file: PickedFile) {
        _uiState.update { state ->
            state.copy(exportConfig = state.exportConfig.copy(pickedFiles = state.exportConfig.pickedFiles - file))
        }
    }

    fun refreshShizukuStatus() {
        _uiState.update { it.copy(shizukuStatus = ShizukuBridge.status(appContext)) }
    }

    fun readSettingsViaShizuku() {
        val res = createLocalizedContext(appContext, _uiState.value.langPref)
        when (ShizukuBridge.status(appContext)) {
            ShizukuBridge.Status.READY -> {
                loadCatalog(ExportCategory.SYSTEM_SETTINGS, force = true)
                _uiState.update { it.copy(statusBannerMessage = res.getString(R.string.shizuku_settings_read)) }
            }
            ShizukuBridge.Status.NO_PERMISSION -> ShizukuBridge.requestPermission { granted ->
                refreshShizukuStatus()
                if (granted) readSettingsViaShizuku()
                else _uiState.update { it.copy(statusBannerMessage = res.getString(R.string.shizuku_denied)) }
            }
            else -> refreshShizukuStatus()
        }
    }

    fun readWifiViaShizuku() {
        val res = createLocalizedContext(appContext, _uiState.value.langPref)
        when (ShizukuBridge.status(appContext)) {
            ShizukuBridge.Status.READY -> Unit
            ShizukuBridge.Status.NO_PERMISSION -> {
                ShizukuBridge.requestPermission { granted ->
                    refreshShizukuStatus()
                    if (granted) readWifiViaShizuku()
                    else _uiState.update { it.copy(statusBannerMessage = res.getString(R.string.shizuku_denied)) }
                }
                return
            }
            else -> {
                refreshShizukuStatus()
                _uiState.update { it.copy(statusBannerMessage = res.getString(R.string.shizuku_not_running)) }
                return
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(shizukuBusy = true) }
            val result = withContext(Dispatchers.IO) { runCatching { ShizukuBridge.readSavedWifiNetworks() } }
            _uiState.update { it.copy(shizukuBusy = false) }
            result.onSuccess { networks ->
                networks.forEach { addManualWifi(it) }
                _uiState.update {
                    it.copy(statusBannerMessage = res.getString(R.string.shizuku_wifi_loaded, networks.size))
                }
            }.onFailure { err ->
                val cause = (err as? java.lang.reflect.InvocationTargetException)?.targetException ?: err
                _uiState.update {
                    it.copy(
                        statusBannerMessage = res.getString(
                            R.string.shizuku_wifi_failed,
                            cause.message ?: cause.javaClass.simpleName
                        )
                    )
                }
            }
        }
    }

    fun tryRootWifiRead() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { WifiExporter.tryReadRootWifiXml() }
            if (found.isEmpty()) {
                _uiState.update { it.copy(statusBannerMessage = "Root Wi-Fi read unavailable (device not rooted or denied). Use QR Scanner or XML import.") }
            } else {
                found.forEach { addManualWifi(it) }
                _uiState.update { it.copy(statusBannerMessage = "Loaded ${found.size} Wi-Fi networks via root") }
            }
        }
    }

    fun suggestedArchiveFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val cfg = _uiState.value.exportConfig
        return if (cfg.encryptArchive && cfg.encryptionPassword.isNotBlank()) {
            "ETG_Backup_$ts.etg"
        } else {
            "ETG_Backup_$ts.etg.zip"
        }
    }

    fun estimatedSelectedBytes(): Long {
        val state = _uiState.value
        return state.exportConfig.selectedCategories.sumOf { cat ->
            state.scanInfos[cat]?.estimatedBytes ?: 0L
        }
    }

    fun exportToSafFile(uri: Uri) {
        val cfg = _uiState.value.exportConfig.copy(destinationMode = TransferMode.LOCAL_FILE)
        if (!validateConfigBeforeExport(cfg)) return
        operationCancelled.set(false)
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val estBytes = estimatedSelectedBytes()
            TransferForegroundService.startOrUpdate(appContext, "ETG Export", "Writing archive...", 0)
            try {
                val manifest = withContext(Dispatchers.IO) {
                    val out = appContext.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Cannot open output file")
                    val pw = if (cfg.encryptArchive) cfg.encryptionPassword else null
                    ZipArchiveSink(out, pw, { operationCancelled.get() }).use { sink ->
                        ExportOrchestrator.runExport(
                            context = appContext,
                            config = cfg,
                            sink = sink,
                            estimatedTotalBytes = estBytes,
                            isCancelled = { operationCancelled.get() },
                            onProgress = ::updateOperationProgress,
                            resolveIssues = ::awaitExportIssueDecisions
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        progress = OperationProgress(phase = OperationPhase.COMPLETED),
                        statusBannerMessage = "Export complete (${manifest.entries.size} entries saved)"
                    )
                }
            } catch (t: Throwable) {
                handleOperationError(t)
            } finally {
                TransferForegroundService.stop(appContext)
            }
        }
    }

    fun exportToUsbTree(treeUri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val cfg = _uiState.value.exportConfig.copy(destinationMode = TransferMode.USB_DRIVE)
        if (!validateConfigBeforeExport(cfg)) return
        operationCancelled.set(false)
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val estBytes = estimatedSelectedBytes()
            val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            TransferForegroundService.startOrUpdate(appContext, "ETG USB Export", "Writing to USB...", 0)
            try {
                val manifest = withContext(Dispatchers.IO) {
                    val rootDir = DocumentFile.fromTreeUri(appContext, treeUri)
                        ?: throw IllegalStateException("Cannot access USB folder")
                    val pw = if (cfg.encryptArchive) cfg.encryptionPassword else null
                    if (cfg.usbUnpackedFolderMode) {
                        val folderName = "ETG_Backup_$ts"
                        val backupDir = rootDir.createDirectory(folderName)
                            ?: throw IllegalStateException("Cannot create $folderName on USB")
                        UsbFolderArchiveSink(appContext, backupDir, pw, { operationCancelled.get() }).use { sink ->
                            ExportOrchestrator.runExport(
                                context = appContext,
                                config = cfg,
                                sink = sink,
                                estimatedTotalBytes = estBytes,
                                isCancelled = { operationCancelled.get() },
                                onProgress = ::updateOperationProgress,
                                resolveIssues = ::awaitExportIssueDecisions
                            )
                        }
                    } else {
                        val fileName = suggestedArchiveFileName()
                        val docFile = rootDir.createFile("application/octet-stream", fileName)
                            ?: throw IllegalStateException("Cannot create $fileName on USB")
                        val out = appContext.contentResolver.openOutputStream(docFile.uri)
                            ?: throw IllegalStateException("Cannot open USB file stream")
                        ZipArchiveSink(out, pw, { operationCancelled.get() }).use { sink ->
                            ExportOrchestrator.runExport(
                                context = appContext,
                                config = cfg,
                                sink = sink,
                                estimatedTotalBytes = estBytes,
                                isCancelled = { operationCancelled.get() },
                                onProgress = ::updateOperationProgress,
                                resolveIssues = ::awaitExportIssueDecisions
                            )
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        progress = OperationProgress(phase = OperationPhase.COMPLETED),
                        statusBannerMessage = "USB Export complete (${manifest.entries.size} entries)"
                    )
                }
            } catch (t: Throwable) {
                handleOperationError(t)
            } finally {
                TransferForegroundService.stop(appContext)
            }
        }
    }

    fun launchTransferAsSender(linkMode: TransferLinkMode) {
        _uiState.update {
            it.copy(
                selectedTab = EtgTab.TRANSFER,
                transferRole = TransferRole.SEND,
                transferLinkMode = linkMode
            )
        }
        startTransferSender()
    }

    fun launchTransferAsReceiver(linkMode: TransferLinkMode) {
        _uiState.update {
            it.copy(
                selectedTab = EtgTab.TRANSFER,
                transferRole = TransferRole.RECEIVE,
                transferLinkMode = linkMode
            )
        }
        refreshNetworkInterfaces()
        if (linkMode == TransferLinkMode.WIRELESS) {
            nsdDiscovery.startDiscovery()
        }
    }

    fun setTransferRole(role: TransferRole) {
        _uiState.update { it.copy(transferRole = role) }
        if (role == TransferRole.RECEIVE && _uiState.value.transferLinkMode == TransferLinkMode.WIRELESS) {
            nsdDiscovery.startDiscovery()
        } else {
            nsdDiscovery.stopDiscovery()
        }
    }

    fun setTransferLinkMode(mode: TransferLinkMode) {
        _uiState.update { it.copy(transferLinkMode = mode) }
        refreshNetworkInterfaces()
        if (mode == TransferLinkMode.WIRELESS && _uiState.value.transferRole == TransferRole.RECEIVE) {
            nsdDiscovery.startDiscovery()
        } else {
            nsdDiscovery.stopDiscovery()
        }
    }

    fun setReceiveFields(host: String, port: String, pin: String) {
        _uiState.update { it.copy(receiveHost = host, receivePort = port, receivePin = pin) }
    }

    fun setReceiveCode(code: String) {
        _uiState.update { it.copy(receiveCode = code.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(9)) }
    }

    fun connectWithPairingUri(raw: String): Boolean {
        val target = TransferProtocol.parsePairingUri(raw) ?: return false
        if (_uiState.value.receiverPhase != ReceiverPhase.IDLE) return true
        startReceive(
            candidates = listOf(target.host to target.port),
            auth = ClientAuth.Token(target.token, target.senderFingerprint),
            peerName = target.deviceName
        )
        return true
    }

    fun connectWithCode() {
        val state = _uiState.value
        val res = createLocalizedContext(appContext, state.langPref)
        if (!TransferProtocol.isValidShortCode(state.receiveCode)) {
            _uiState.update { it.copy(statusBannerMessage = res.getString(R.string.transfer_err_code_format)) }
            return
        }
        val manualPort = state.receivePort.toIntOrNull() ?: state.transferPort
        val candidates = buildList {
            if (state.receiveHost.isNotBlank()) add(state.receiveHost.trim() to manualPort)
            state.wifiDirectState.groupOwnerIp?.takeIf { !state.wifiDirectState.isGroupOwner && it.isNotBlank() }
                ?.let { add(it to state.transferPort) }
            state.discoveredPeers.forEach { add(it.host to it.port) }
        }.distinct()
        if (candidates.isEmpty()) {
            nsdDiscovery.startDiscovery()
            _uiState.update { it.copy(statusBannerMessage = res.getString(R.string.transfer_err_no_sender)) }
            return
        }
        startReceive(candidates, ClientAuth.Code(state.receiveCode), peerName = "")
    }

    fun cancelReceive() {
        operationCancelled.set(true)
        receiveJob?.cancel()
        receiveJob = null
        _uiState.update { it.copy(receiverPhase = ReceiverPhase.IDLE, receiverVerificationCode = "") }
    }

    fun respondToPeerRequest(allow: Boolean) {
        pendingPeerDecision?.complete(allow)
    }

    private fun awaitPeerDecision(request: PeerRequest): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pendingPeerDecision = decision
        _uiState.update { it.copy(pendingPeerRequest = request) }
        TransferForegroundService.startOrUpdate(
            appContext,
            "ETG",
            "${request.deviceName} • ${request.verificationCode}"
        )
        return try {
            runBlocking { withTimeoutOrNull(115_000) { decision.await() } ?: false }
        } finally {
            pendingPeerDecision = null
            _uiState.update { it.copy(pendingPeerRequest = null) }
        }
    }

    fun toggleWebServer(enabled: Boolean) {
        _uiState.update { it.copy(webServerEnabled = enabled) }
        if (!enabled) {
            stopWebServerOnly()
        } else if (_uiState.value.senderRunning) {
            startWebServerBuildIfNeeded()
        }
    }

    fun startTransferSender() {
        val base = _uiState.value.exportConfig
        val cfg = base.copy(
            destinationMode = if (_uiState.value.transferLinkMode == TransferLinkMode.WIRED) {
                TransferMode.WIRED_CABLE
            } else TransferMode.WIRELESS,

            encryptArchive = base.encryptArchive && base.encryptionPassword.isNotBlank()
        )
        if (!validateConfigBeforeExport(cfg)) return
        stopTransferSender()

        val pin = TransferProtocol.generatePin()
        val session = TransferProtocol.newSenderSession("${Build.MANUFACTURER} ${Build.MODEL}")
        val port = _uiState.value.transferPort
        val estBytes = estimatedSelectedBytes()
        val fileName = suggestedArchiveFileName()
        operationCancelled.set(false)

        refreshNetworkInterfaces()
        val host = if (_uiState.value.transferLinkMode == TransferLinkMode.WIRED) {
            NetworkInterfaceInspector.bestWiredHost(_uiState.value.netInterfaces)
        } else {
            NetworkInterfaceInspector.bestWirelessHost(_uiState.value.netInterfaces)
        }
        val pairingUri = session.pairingUri(host, port)
        NfcPairing.activePayload = pairingUri
        _uiState.update {
            it.copy(
                senderRunning = true,
                senderPin = pin,
                senderShortCode = session.shortCode,
                senderPairingUri = pairingUri,
                senderStatusMessage = ""
            )
        }

        if (_uiState.value.transferLinkMode == TransferLinkMode.WIRELESS) {
            nsdDiscovery.startAdvertising(port)
        }
        if (_uiState.value.webServerEnabled) {
            startWebServerBuildIfNeeded(pinOverride = pin)
        }

        senderServerJob = viewModelScope.launch {
            TransferForegroundService.startOrUpdate(
                appContext,
                "ETG Transfer",
                TransferProtocol.formatShortCode(session.shortCode)
            )
            try {
                withContext(Dispatchers.IO) {
                    val ss = ServerSocket(port).apply { reuseAddress = true }
                    activeServerSocket = ss
                    TransferProtocol.serveSingleTransfer(
                        serverSocket = ss,
                        session = session,
                        headerInfo = TransferHeaderInfo(fileName, estBytes, "${Build.MANUFACTURER} ${Build.MODEL}"),
                        confirm = ::awaitPeerDecision,
                        isCancelled = { operationCancelled.get() || ss.isClosed },
                        onClientConnected = {
                            NfcPairing.activePayload = null
                            _uiState.update { it.copy(senderStatusMessage = "") }
                        },
                        onProgress = { stats ->
                            updateOperationProgress(
                                OperationProgress(
                                    phase = OperationPhase.TRANSFERRING,
                                    currentItemLabel = fileName,
                                    processedBytes = stats.bytesTransferred,
                                    totalBytes = maxOf(estBytes, stats.bytesTransferred),
                                    speedBytesPerSec = stats.bytesPerSec,
                                    etaSeconds = stats.etaSeconds
                                )
                            )
                        },
                        payloadWriter = { transferOut ->
                            val pw = if (cfg.encryptArchive) cfg.encryptionPassword else null
                            ZipArchiveSink(transferOut, pw, { operationCancelled.get() }).use { sink ->
                                ExportOrchestrator.runExport(
                                    context = appContext,
                                    config = cfg,
                                    sink = sink,
                                    estimatedTotalBytes = estBytes,
                                    isCancelled = { operationCancelled.get() },
                                    onProgress = ::updateOperationProgress
                                )
                            }
                        }
                    )
                }
                _uiState.update {
                    it.copy(
                        senderRunning = false,
                        senderStatusMessage = createLocalizedContext(appContext, it.langPref)
                            .getString(R.string.transfer_sent_ok),
                        progress = OperationProgress(phase = OperationPhase.COMPLETED)
                    )
                }
            } catch (t: Throwable) {
                if (!operationCancelled.get()) {
                    _uiState.update {
                        it.copy(
                            senderRunning = false,
                            senderStatusMessage = "Server stopped: ${t.message ?: "error"}",
                            progress = OperationProgress(phase = OperationPhase.IDLE)
                        )
                    }
                }
            } finally {
                NfcPairing.activePayload = null
                nsdDiscovery.stopAdvertising()
                runCatching { activeServerSocket?.close() }
                activeServerSocket = null
                TransferForegroundService.stop(appContext)
            }
        }
    }

    private fun startWebServerBuildIfNeeded(pinOverride: String? = null) {
        val state = _uiState.value
        val cfg = state.exportConfig
        if (!validateConfigBeforeExport(cfg)) return
        val pin = pinOverride ?: state.senderPin
        viewModelScope.launch {
            _uiState.update { it.copy(senderStatusMessage = "Preparing archive for PIN-protected PC download...") }
            runCatching {
                val outFile = File(appContext.cacheDir, suggestedArchiveFileName())
                val estBytes = estimatedSelectedBytes()
                withContext(Dispatchers.IO) {
                    val pw = if (cfg.encryptArchive) cfg.encryptionPassword else null
                    ZipArchiveSink(outFile.outputStream(), pw, { operationCancelled.get() }).use { sink ->
                        ExportOrchestrator.runExport(
                            context = appContext,
                            config = cfg,
                            sink = sink,
                            estimatedTotalBytes = estBytes,
                            isCancelled = { operationCancelled.get() },
                            onProgress = ::updateOperationProgress
                        )
                    }
                }
                stopWebServerOnly()
                webTempArchiveFile = outFile
                val srv = LocalWebDownloadServer(
                    archiveFile = outFile,
                    downloadFileName = outFile.name,
                    pin = pin,
                    port = state.webServerPort
                )
                srv.start()
                webServer = srv
                _uiState.update {
                    it.copy(
                        webServerRunning = true,
                        progress = OperationProgress(phase = OperationPhase.IDLE),
                        senderStatusMessage = "PC Web Download ready on port ${state.webServerPort} (PIN: $pin)"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        webServerRunning = false,
                        progress = OperationProgress(phase = OperationPhase.IDLE),
                        statusBannerMessage = err.message ?: "Failed to start PC web server"
                    )
                }
            }
        }
    }

    private fun stopWebServerOnly() {
        webServer?.stop()
        webServer = null
        webTempArchiveFile?.delete()
        webTempArchiveFile = null
        _uiState.update { it.copy(webServerRunning = false) }
    }

    fun stopTransferSender() {
        operationCancelled.set(true)
        NfcPairing.activePayload = null
        pendingPeerDecision?.complete(false)
        nsdDiscovery.stopAdvertising()
        runCatching { activeServerSocket?.close() }
        activeServerSocket = null
        senderServerJob?.cancel()
        senderServerJob = null
        stopWebServerOnly()
        TransferForegroundService.stop(appContext)
        _uiState.update {
            it.copy(
                senderRunning = false,
                senderStatusMessage = "",
                senderShortCode = "",
                senderPairingUri = "",
                pendingPeerRequest = null,
                progress = OperationProgress(phase = OperationPhase.IDLE)
            )
        }
    }

    fun createWifiDirectGroup() = wifiDirect.createGroup()
    fun stopWifiDirectGroup() = wifiDirect.removeGroup()
    fun discoverWifiDirectPeers() = wifiDirect.discoverPeers()
    fun connectWifiDirectPeer(address: String) = wifiDirect.connectToPeer(address)

    private fun startReceive(candidates: List<Pair<String, Int>>, auth: ClientAuth, peerName: String) {
        val res = createLocalizedContext(appContext, _uiState.value.langPref)
        operationCancelled.set(false)
        receiveJob?.cancel()
        _uiState.update {
            it.copy(receiverPhase = ReceiverPhase.CONNECTING, receiverPeerName = peerName, receiverVerificationCode = "")
        }
        receiveJob = viewModelScope.launch {
            TransferForegroundService.startOrUpdate(appContext, "ETG", res.getString(R.string.transfer_receiving))
            try {
                val receivedFile = withContext(Dispatchers.IO) {
                    var lastError: Throwable? = null
                    for ((host, port) in candidates) {
                        if (operationCancelled.get()) break
                        var targetFile: File? = null
                        val attempt = runCatching {
                            TransferProtocol.receiveFromSender(
                                host = host,
                                port = port,
                                auth = auth,
                                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                                connectTimeoutMs = if (candidates.size > 1) 5_000 else 15_000,
                                outputFactory = { header ->
                                    _uiState.update { it.copy(receiverPhase = ReceiverPhase.RECEIVING) }
                                    val safeName = header.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                    File(appContext.cacheDir, "received_$safeName").also { targetFile = it }.outputStream()
                                },
                                onAwaitingApproval = { sas, sender ->
                                    _uiState.update {
                                        it.copy(
                                            receiverPhase = ReceiverPhase.AWAITING_APPROVAL,
                                            receiverVerificationCode = sas,
                                            receiverPeerName = sender
                                        )
                                    }
                                },
                                isCancelled = { operationCancelled.get() },
                                onProgress = { stats ->
                                    updateOperationProgress(
                                        OperationProgress(
                                            phase = OperationPhase.TRANSFERRING,
                                            currentItemLabel = _uiState.value.receiverPeerName,
                                            processedBytes = stats.bytesTransferred,
                                            totalBytes = stats.totalBytes,
                                            speedBytesPerSec = stats.bytesPerSec,
                                            etaSeconds = stats.etaSeconds
                                        )
                                    )
                                }
                            )
                        }
                        if (attempt.isSuccess) return@withContext targetFile
                        lastError = attempt.exceptionOrNull()

                        if (lastError is TransferRejectedException || _uiState.value.receiverPhase == ReceiverPhase.RECEIVING) break
                    }
                    throw lastError ?: IllegalStateException(res.getString(R.string.transfer_err_no_sender))
                } ?: throw IllegalStateException("No payload received")
                _uiState.update {
                    it.copy(
                        selectedTab = EtgTab.IMPORT,
                        receiverPhase = ReceiverPhase.IDLE,
                        receiveCode = "",
                        progress = OperationProgress(phase = OperationPhase.IDLE)
                    )
                }
                inspectImportSource(ArchiveSource.LocalFile(receivedFile), password = null)
            } catch (t: Throwable) {
                val friendly = when (t) {
                    is TransferRejectedException -> res.getString(R.string.transfer_err_rejected)
                    is TransferAuthException -> if (auth is ClientAuth.Code) {
                        res.getString(R.string.transfer_err_wrong_code)
                    } else res.getString(R.string.transfer_err_identity)
                    is java.net.ConnectException, is java.net.SocketTimeoutException ->
                        res.getString(R.string.transfer_err_no_sender)
                    else -> null
                }
                _uiState.update { it.copy(receiverPhase = ReceiverPhase.IDLE, receiverVerificationCode = "") }
                if (friendly != null && !operationCancelled.get()) {
                    _uiState.update {
                        it.copy(statusBannerMessage = friendly, progress = OperationProgress(phase = OperationPhase.IDLE))
                    }
                } else {
                    handleOperationError(t)
                }
            } finally {
                TransferForegroundService.stop(appContext)
            }
        }
    }

    fun openImportSafFile(uri: Uri) {
        val name = resolveDisplayName(uri) ?: "archive.etg"
        inspectImportSource(ArchiveSource.SafUri(uri, name), password = null)
    }

    fun openImportUsbFolder(treeUri: Uri) {
        val doc = DocumentFile.fromTreeUri(appContext, treeUri)
        val name = doc?.name ?: "USB Folder"
        inspectImportSource(ArchiveSource.UsbFolder(treeUri, name), password = null)
    }

    fun setImportPassword(pw: String) {
        _uiState.update { it.copy(importPassword = pw) }
    }

    fun unlockImportArchive() {
        val src = _uiState.value.importSource ?: return
        inspectImportSource(src, _uiState.value.importPassword)
    }

    fun inspectImportSource(source: ArchiveSource, password: String?) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    importSource = source,
                    importResult = null,
                    progress = OperationProgress(phase = OperationPhase.INSPECTING, currentItemLabel = "Inspecting archive...")
                )
            }
            val preview = withContext(Dispatchers.IO) {
                EtgArchiveReader.inspect(appContext, source, password)
            }
            _uiState.update {
                it.copy(
                    importPreview = preview,
                    importSelectedCategories = preview.availableCategories,
                    progress = OperationProgress(phase = OperationPhase.IDLE)
                )
            }
        }
    }

    fun toggleImportCategory(cat: ExportCategory) {
        _uiState.update { state ->
            val next = if (cat in state.importSelectedCategories) {
                state.importSelectedCategories - cat
            } else state.importSelectedCategories + cat
            state.copy(importSelectedCategories = next)
        }
    }

    fun runRestore() {
        val state = _uiState.value
        val source = state.importSource ?: return
        val preview = state.importPreview ?: return
        if (!preview.isUnlocked || state.importSelectedCategories.isEmpty()) return

        operationCancelled.set(false)
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            TransferForegroundService.startOrUpdate(appContext, "ETG Restore", "Restoring to GrapheneOS...")
            try {
                val result = withContext(Dispatchers.IO) {
                    val pw = if (preview.isEncrypted) state.importPassword else null
                    EtgArchiveReader.restore(
                        context = appContext,
                        source = source,
                        preview = preview,
                        password = pw,
                        selectedCategories = state.importSelectedCategories,
                        onProgress = ::updateOperationProgress
                    )
                }
                _uiState.update {
                    it.copy(
                        importResult = result,
                        progress = OperationProgress(phase = OperationPhase.COMPLETED),
                        statusBannerMessage = if (result.issues.isEmpty()) "Restore finished!" else null,
                        issueSession = result.issues.takeIf { list -> list.isNotEmpty() }
                            ?.let { list -> IssueSession(IssuePhase.IMPORT, list, list) }
                    )
                }
                refreshSystemCapabilities()
            } catch (t: Throwable) {
                handleOperationError(t)
            } finally {
                TransferForegroundService.stop(appContext)
            }
        }
    }

    private fun awaitExportIssueDecisions(log: IssueLog) {
        val done = CompletableDeferred<Unit>()
        exportIssueLog = log
        exportIssuesDone = done
        val list = log.all()
        _uiState.update { it.copy(issueSession = IssueSession(IssuePhase.EXPORT, list, list)) }
        TransferForegroundService.startOrUpdate(appContext, "ETG", "${list.size} item(s) need attention")
        runBlocking { done.await() }
        exportIssueLog = null
        exportIssuesDone = null
    }

    fun skipIssue(applyToAll: Boolean) {
        val session = _uiState.value.issueSession ?: return
        if (session.busy || session.pending.isEmpty()) return
        val moved = if (applyToAll) session.pending else session.pending.take(1)
        updateSession(session.copy(pending = session.pending - moved.toSet(), skipped = session.skipped + moved))
    }

    fun retryIssue(applyToAll: Boolean) {
        val session = _uiState.value.issueSession ?: return
        if (session.busy || session.pending.isEmpty()) return
        val candidates = if (applyToAll) session.pending else session.pending.take(1)
        val targets = candidates.filter { it.retryable }
        val notRetryable = candidates - targets.toSet()
        _uiState.update { it.copy(issueSession = session.copy(busy = true)) }
        viewModelScope.launch {
            val stillFailing: Map<String, TransferIssue> = withContext(Dispatchers.IO) {
                runCatching {
                    if (session.phase == IssuePhase.EXPORT) retryExportIssues(targets)
                    else retryImportIssues(targets)
                }.getOrElse { t ->
                    val (reason, detail) = IssueLog.reasonOf(t)
                    targets.associate { it.id to it.copy(reason = reason, reasonDetail = detail, attempts = it.attempts + 1) }
                }
            }
            val current = _uiState.value.issueSession ?: return@launch
            val fixedNow = targets.count { it.id !in stillFailing }
            val handled = candidates.map { it.id }.toSet()
            val failedAgain = targets.mapNotNull { stillFailing[it.id] }
            val next = if (applyToAll) {
                current.copy(
                    pending = current.pending.filter { it.id !in handled },
                    skipped = current.skipped + failedAgain + notRetryable,
                    fixed = current.fixed + fixedNow,
                    busy = false
                )
            } else {
                val rest = current.pending.filter { it.id !in handled }
                current.copy(
                    pending = failedAgain + notRetryable + rest,
                    fixed = current.fixed + fixedNow,
                    busy = false
                )
            }
            updateSession(next)
            if (fixedNow > 0 && session.phase == IssuePhase.IMPORT) refreshSystemCapabilities()
        }
    }

    fun dismissIssues() {
        _uiState.update { it.copy(issueSession = null) }
    }

    private fun updateSession(session: IssueSession) {
        if (session.pending.isEmpty() && !session.finished) {
            exportIssuesDone?.complete(Unit)
            val summary = session.copy(finished = true, busy = false)
            _uiState.update {
                it.copy(issueSession = if (summary.skipped.isEmpty()) null else summary)
            }
            if (summary.skipped.isEmpty()) {
                _uiState.update { it.copy(statusBannerMessage = createLocalizedContext(appContext, it.langPref).getString(R.string.issue_all_fixed)) }
            }
        } else {
            _uiState.update { it.copy(issueSession = session) }
        }
    }

    private fun retryExportIssues(targets: List<TransferIssue>): Map<String, TransferIssue> {
        val log = exportIssueLog ?: return targets.associateBy { it.id }
        return targets.mapNotNull { issue -> log.retry(issue.id)?.let { issue.id to it } }.toMap()
    }

    private fun retryImportIssues(targets: List<TransferIssue>): Map<String, TransferIssue> {
        val state = _uiState.value
        val failing = mutableMapOf<String, TransferIssue>()

        val settingTargets = targets.filter { it.settingId.isNotBlank() }
        if (settingTargets.isNotEmpty()) {
            val snapshot = state.importResult?.settingsSnapshot ?: state.importPreview?.settingsSnapshot
            val ids = settingTargets.map { it.settingId }.toSet()
            val (_, again) = SettingsExporter.importSettings(appContext, snapshot, null, onlyIds = ids)
            val againById = again.associateBy { it.settingId }
            settingTargets.forEach { t ->
                againById[t.settingId]?.let { n ->
                    failing[t.id] = t.copy(reason = n.reason, reasonDetail = n.reasonDetail, attempts = t.attempts + 1)
                }
            }
        }

        val pathTargets = targets.filter { it.settingId.isBlank() && it.archivePath.isNotBlank() }
        if (pathTargets.isNotEmpty()) {
            val source = state.importSource
            val preview = state.importPreview
            if (source == null || preview == null) {
                pathTargets.forEach { failing[it.id] = it.copy(attempts = it.attempts + 1) }
            } else {
                val everything = pathTargets.any { it.archivePath == "*" }
                val paths = pathTargets.flatMap { it.archivePath.split('\n') }.filter { it != "*" }.toSet()
                val result = EtgArchiveReader.restore(
                    context = appContext,
                    source = source,
                    preview = preview,
                    password = if (preview.isEncrypted) state.importPassword else null,
                    selectedCategories = state.importSelectedCategories,
                    onProgress = {},
                    onlyPaths = if (everything) null else paths
                )
                val againByPath = result.issues.filter { it.phase == IssuePhase.IMPORT }
                pathTargets.forEach { t ->
                    val n = if (t.archivePath == "*") {
                        result.issues.firstOrNull { it.reason == IssueReason.READ_INTERRUPTED }
                    } else {
                        againByPath.firstOrNull { it.archivePath == t.archivePath }
                    }
                    if (n != null) {
                        failing[t.id] = t.copy(reason = n.reason, reasonDetail = n.reasonDetail, attempts = t.attempts + 1)
                    }
                }
            }
        }
        return failing
    }

    fun installExtractedApk(packageName: String) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                val files = EtgArchiveReader.resolveExtractedApkFiles(appContext, packageName)
                AppsExporter.installApkFiles(appContext, files)
            }
            res.onFailure { err ->
                _uiState.update { it.copy(statusBannerMessage = "APK install error: ${err.message}") }
            }
        }
    }

    fun hasExtractedApksForPackage(packageName: String): Boolean =
        EtgArchiveReader.resolveExtractedApkFiles(appContext, packageName).isNotEmpty()

    fun cancelActiveOperation() {
        operationCancelled.set(true)
        exportIssuesDone?.complete(Unit)
        _uiState.update { it.copy(issueSession = null) }
        activeJob?.cancel()
        activeJob = null
        stopTransferSender()
        TransferForegroundService.stop(appContext)
        _uiState.update { it.copy(progress = OperationProgress(phase = OperationPhase.CANCELLED)) }
    }

    private fun validateConfigBeforeExport(cfg: ExportConfig): Boolean {
        if (cfg.selectedCategories.isEmpty()) {
            _uiState.update { it.copy(statusBannerMessage = "Select at least one category to export") }
            return false
        }
        if (cfg.encryptArchive && cfg.encryptionPassword.length < 4) {
            _uiState.update { it.copy(statusBannerMessage = "Encryption password must be at least 4 characters (or disable encryption)") }
            return false
        }
        return true
    }

    private fun updateOperationProgress(p: OperationProgress) {
        _uiState.update { it.copy(progress = p) }
    }

    private fun handleOperationError(t: Throwable) {
        if (operationCancelled.get()) {
            _uiState.update { it.copy(progress = OperationProgress(phase = OperationPhase.CANCELLED)) }
            return
        }
        _uiState.update {
            it.copy(
                progress = OperationProgress(phase = OperationPhase.ERROR, errorMessage = t.message),
                statusBannerMessage = t.message ?: "Operation failed"
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    override fun onCleared() {
        super.onCleared()
        stopTransferSender()
        nsdDiscovery.stopDiscovery()
        wifiDirect.unregister()
    }
}
