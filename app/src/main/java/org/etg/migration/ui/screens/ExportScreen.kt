package org.etg.migration.ui.screens

import org.etg.migration.data.integration.SinusChatBridge
import org.etg.migration.ui.components.SinusHandoffPanel
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.etg.migration.R
import org.etg.migration.data.PermissionHelper
import org.etg.migration.data.storage.StorageInspector
import org.etg.migration.model.CategoryScanInfo
import org.etg.migration.model.CategoryStatus
import org.etg.migration.data.shizuku.ShizukuBridge
import org.etg.migration.data.exporter.SelectableItem
import org.etg.migration.data.exporter.SelectionCatalog
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.PickedFile
import org.etg.migration.ui.components.FolderPreviewDialog
import org.etg.migration.ui.components.SelectionSheet
import org.etg.migration.model.OtpAccount
import org.etg.migration.model.WifiNetworkEntry
import org.etg.migration.ui.EtgUiState
import org.etg.migration.ui.EtgViewModel
import org.etg.migration.ui.TransferLinkMode
import org.etg.migration.ui.components.ManualWifiDialog
import org.etg.migration.ui.components.QrScannerDialog
import org.etg.migration.ui.components.WifiQrPreviewDialog
import org.etg.migration.ui.theme.EtgBanner
import org.etg.migration.ui.theme.EtgButton
import org.etg.migration.ui.theme.EtgButtonSize
import org.etg.migration.ui.theme.EtgButtonStyle
import org.etg.migration.ui.theme.EtgCard
import org.etg.migration.ui.theme.EtgCardHeader
import org.etg.migration.ui.theme.EtgCheckbox
import org.etg.migration.ui.theme.EtgDimens
import org.etg.migration.ui.theme.EtgDivider
import org.etg.migration.ui.theme.EtgIconButton
import org.etg.migration.ui.theme.EtgInsetPanel
import org.etg.migration.ui.theme.EtgPasswordField
import org.etg.migration.ui.theme.EtgProgressBar
import org.etg.migration.ui.theme.EtgScreenHeader
import org.etg.migration.ui.theme.EtgSwitchRow
import org.etg.migration.ui.theme.EtgTone
import org.etg.migration.ui.theme.FlatSectionContainer
import org.etg.migration.ui.theme.FormatUtils
import org.etg.migration.ui.theme.GrapheneHeroCard
import org.etg.migration.ui.theme.IconBadge
import org.etg.migration.ui.theme.LocalGrapheneColors
import org.etg.migration.ui.theme.MonoTechnicalStyle
import org.etg.migration.ui.theme.NumericStyle
import org.etg.migration.ui.theme.PasswordStrengthBar
import org.etg.migration.ui.theme.SectionHeader
import org.etg.migration.ui.theme.StatusDot
import org.etg.migration.ui.theme.StatusPill
import org.etg.migration.ui.theme.etgSwitchColors

private val CATEGORIES_WITH_OPTIONS = setOf(
    ExportCategory.CALENDAR,
    ExportCategory.AUDIO_RINGTONES,
    ExportCategory.CONTACTS,
    ExportCategory.SYSTEM_SETTINGS,
    ExportCategory.MESSAGES_SMS_MMS,
    ExportCategory.PHOTOS_VIDEOS,
    ExportCategory.APPS_AND_APKS,
    ExportCategory.CHAT_BACKUPS,
    ExportCategory.DOCUMENTS_DOWNLOADS,
    ExportCategory.VAULT_KEYS_2FA,
    ExportCategory.WIFI_NETWORKS
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    state: EtgUiState,
    viewModel: EtgViewModel
) {
    val context = LocalContext.current
    val g = LocalGrapheneColors.current
    var expandedCategories by remember { mutableStateOf(emptySet<ExportCategory>()) }
    var showDestinationSheet by remember { mutableStateOf(false) }
    var showWifiQrScanner by remember { mutableStateOf(false) }
    var showOtpScanner by remember { mutableStateOf(false) }
    var selectionCategory by remember { mutableStateOf<ExportCategory?>(null) }
    var previewFolder by remember { mutableStateOf<SelectableItem?>(null) }
    var pickTarget by remember { mutableStateOf(ExportCategory.VAULT_KEYS_2FA) }
    val pickFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.addPickedFiles(uris, pickTarget) }
    val invalidOtpMsg = stringResource(R.string.otp_invalid_qr)
    val sinusExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> viewModel.receiveSinusChatExport(r.resultCode, r.data) }
    var showManualWifiDialog by remember { mutableStateOf(false) }
    var previewWifiEntry by remember { mutableStateOf<WifiNetworkEntry?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshAllScans()
    }

    val createSafFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            showDestinationSheet = false
            viewModel.exportToSafFile(uri)
        }
    }

    val usbTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            showDestinationSheet = false
            viewModel.exportToUsbTree(uri)
        }
    }

    val extraFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.addExtraSafFolder(uri)
    }

    val smsRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshSystemCapabilities()
        viewModel.refreshAllScans()
    }

    fun requestSmsRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                smsRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            smsRoleLauncher.launch(
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                }
            )
        }
    }

    fun openDefaultAppsSettings() {
        runCatching { smsRoleLauncher.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
    }

    val wifiXmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importWifiConfigXmlUri(uri)
    }

    fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            }
        }
    }

    val selectedCount = state.exportConfig.selectedCategories.size
    val totalCategories = ExportCategory.entries.size
    val totalEstBytes = state.exportConfig.selectedCategories.sumOf {
        state.scanInfos[it]?.estimatedBytes ?: 0L
    }
    val needsPermissionsAny = state.scanInfos.values.any { it.status == CategoryStatus.NEEDS_PERMISSION }
    val selectionFraction = if (totalCategories > 0) selectedCount.toFloat() / totalCategories.toFloat() else 0f

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = EtgDimens.screenGutter,
                end = EtgDimens.screenGutter,
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "screen_header") {
                EtgScreenHeader(
                    eyebrow = stringResource(R.string.screen_export_eyebrow),
                    title = stringResource(R.string.screen_export_title),
                    trailing = {
                        EtgIconButton(
                            iconRes = R.drawable.ic_refresh,
                            onClick = { viewModel.refreshAllScans() },
                            contentDescription = stringResource(R.string.action_rescan),
                            size = 40.dp
                        )
                    }
                )
            }

            item(key = "hero_summary") {
                GrapheneHeroCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StatusPill(
                            text = "${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                            color = g.accentCyan,
                            showDot = true
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.export_estimated_size).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = g.textTertiary
                            )
                            Text(
                                text = if (state.isScanning) {
                                    stringResource(R.string.export_scanning)
                                } else {
                                    FormatUtils.formatBytes(totalEstBytes)
                                },
                                style = if (state.isScanning) {
                                    MaterialTheme.typography.headlineSmall
                                } else {
                                    MaterialTheme.typography.displaySmall
                                },
                                color = g.textPrimary
                            )
                            Text(
                                text = stringResource(
                                    R.string.export_selected_summary,
                                    selectedCount,
                                    totalCategories,
                                    FormatUtils.formatBytes(totalEstBytes)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            EtgProgressBar(fraction = if (state.isScanning) null else selectionFraction)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.export_categories_section),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textTertiary
                                )
                                Text(
                                    text = "$selectedCount / $totalCategories",
                                    style = NumericStyle,
                                    color = g.textSecondary
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            EtgButton(
                                text = stringResource(R.string.action_select_all),
                                onClick = { viewModel.selectAllCategories() },
                                style = if (selectedCount == totalCategories) EtgButtonStyle.TONAL else EtgButtonStyle.OUTLINE,
                                tone = if (selectedCount == totalCategories) EtgTone.ACCENT else EtgTone.NEUTRAL,
                                size = EtgButtonSize.SMALL,
                                modifier = Modifier.weight(1f)
                            )
                            EtgButton(
                                text = stringResource(R.string.action_select_none),
                                onClick = { viewModel.clearAllCategories() },
                                style = if (selectedCount == 0) EtgButtonStyle.TONAL else EtgButtonStyle.OUTLINE,
                                tone = if (selectedCount == 0) EtgTone.ACCENT else EtgTone.NEUTRAL,
                                size = EtgButtonSize.SMALL,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (needsPermissionsAny || !state.hasAllFilesAccess) {
                item(key = "permission_banner") {
                    EtgBanner(
                        tone = EtgTone.WARNING,
                        iconRes = R.drawable.ic_shield,
                        text = stringResource(R.string.export_permission_banner)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            EtgButton(
                                text = stringResource(R.string.action_grant_permission),
                                onClick = { permLauncher.launch(PermissionHelper.allCoreRuntimePermissions()) },
                                tone = EtgTone.WARNING,
                                style = EtgButtonStyle.TONAL,
                                size = EtgButtonSize.SMALL,
                                modifier = Modifier.weight(1f)
                            )
                            if (!state.hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                EtgButton(
                                    text = stringResource(R.string.action_grant_all_files),
                                    onClick = { openAllFilesAccessSettings() },
                                    tone = EtgTone.NEUTRAL,
                                    style = EtgButtonStyle.OUTLINE,
                                    size = EtgButtonSize.SMALL,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            item(key = "categories_header") {
                SectionHeader(
                    title = stringResource(R.string.export_categories_section),
                    modifier = Modifier.padding(top = 10.dp, bottom = 0.dp),
                    trailing = {
                        Text(
                            text = "$selectedCount / $totalCategories",
                            style = NumericStyle,
                            color = g.textTertiary
                        )
                    }
                )
            }

            items(ExportCategory.entries, key = { cat -> cat.name }) { category ->
                val scanInfo = state.scanInfos[category] ?: CategoryScanInfo(category)
                val checked = category in state.exportConfig.selectedCategories
                val expanded = category in expandedCategories
                val hasOptions = category in CATEGORIES_WITH_OPTIONS || scanInfo.status == CategoryStatus.NEEDS_PERMISSION

                CategoryCardRow(
                    category = category,
                    scanInfo = scanInfo,
                    checked = checked,
                    expanded = expanded,
                    hasOptions = hasOptions,
                    state = state,
                    onToggleChecked = { viewModel.toggleCategory(category) },
                    onToggleExpand = {
                        if (hasOptions) {
                            expandedCategories = if (expanded) expandedCategories - category else expandedCategories + category
                        } else {
                            viewModel.toggleCategory(category)
                        }
                    },
                    onRequestPermissions = { perms ->
                        if (perms.isNotEmpty()) {
                            permLauncher.launch(perms.toTypedArray())
                        } else {
                            openAllFilesAccessSettings()
                        }
                    },
                    onToggleVideos = { viewModel.setIncludeVideos(it) },
                    onToggleApks = { viewModel.setIncludeApks(it) },
                    onAddSafFolder = { extraFolderLauncher.launch(null) },
                    onRemoveSafFolder = { viewModel.removeExtraSafFolder(it) },
                    onScanWifiQr = { showWifiQrScanner = true },
                    onAddManualWifi = { showManualWifiDialog = true },
                    onImportWifiXml = { wifiXmlLauncher.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                    onTryRootWifi = { viewModel.tryRootWifiRead() },
                    onPreviewWifiQr = { previewWifiEntry = it },
                    onRemoveWifi = { viewModel.removeWifi(it) },
                    onRequestSmsRole = { requestSmsRole() },
                    onOpenDefaultApps = { openDefaultAppsSettings() },
                    onScanOtpQr = { showOtpScanner = true },
                    onRemoveOtp = { viewModel.removeAuthenticatorAccount(it) },
                    onReadWifiShizuku = { viewModel.readWifiViaShizuku() },
                    onShizukuSettings = { viewModel.readSettingsViaShizuku() },
                    onOpenSelection = {
                        viewModel.loadCatalog(category)
                        selectionCategory = category
                    },
                    onPickFiles = {
                        pickTarget = category
                        pickFilesLauncher.launch(arrayOf("*/*"))
                    },
                    onRemovePicked = { viewModel.removePickedFile(it) },
                    onSinusChatExport = {
                        val ok = runCatching { sinusExportLauncher.launch(SinusChatBridge.exportIntent()) }.isSuccess
                        viewModel.sinusChatExportStarted(ok)
                    },
                    onOpenShizuku = {
                        runCatching { context.startActivity(ShizukuBridge.launchIntent(context)) }
                    }
                )
            }

            item(key = "security_header") {
                SectionHeader(
                    title = stringResource(R.string.export_security_section),
                    modifier = Modifier.padding(top = 10.dp),
                    trailing = {
                        if (state.exportConfig.encryptArchive) {
                            StatusPill(text = "AES-256-GCM", color = g.verifiedEmerald, showDot = true)
                        }
                    }
                )
            }

            item(key = "encryption_card") {
                val encrypted = state.exportConfig.encryptArchive
                val borderCol by animateColorAsState(
                    targetValue = if (encrypted) g.verifiedEmerald.copy(alpha = 0.38f) else g.border,
                    animationSpec = tween(220),
                    label = "encBorder"
                )
                EtgCard(borderColor = borderCol) {
                    EtgCardHeader(
                        iconRes = R.drawable.ic_lock,
                        tint = if (encrypted) g.verifiedEmerald else g.textSecondary,
                        title = stringResource(R.string.export_encryption_title),
                        subtitle = "PBKDF2-SHA256 · AES-256-GCM",
                        subtitleMono = true,
                        trailing = {
                            Switch(
                                checked = encrypted,
                                onCheckedChange = { viewModel.setEncryptArchive(it) },
                                colors = etgSwitchColors()
                            )
                        }
                    )

                    AnimatedVisibility(
                        visible = encrypted,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            EtgDivider()
                            Text(
                                text = stringResource(R.string.export_encryption_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                            EtgPasswordField(
                                value = state.exportConfig.encryptionPassword,
                                onValueChange = { viewModel.setEncryptionPassword(it) },
                                label = stringResource(R.string.export_password_label),
                                placeholder = stringResource(R.string.export_password_hint)
                            )
                            PasswordStrengthBar(password = state.exportConfig.encryptionPassword)
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(g.background.copy(alpha = 0.92f))
        ) {
            HorizontalDivider(thickness = 1.dp, color = g.border)
            EtgButton(
                text = stringResource(
                    R.string.export_start_button,
                    selectedCount,
                    FormatUtils.formatBytes(totalEstBytes)
                ),
                iconRes = if (state.exportConfig.encryptArchive) R.drawable.ic_lock else R.drawable.ic_upload,
                enabled = selectedCount > 0,
                onClick = {
                    viewModel.refreshSystemCapabilities()
                    showDestinationSheet = true
                },
                size = EtgButtonSize.LARGE,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EtgDimens.screenGutter, vertical = 12.dp)
            )
        }
    }

    if (showDestinationSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDestinationSheet = false },
            containerColor = g.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(g.borderStrong)
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EtgDimens.screenGutter)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.export_select_destination_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = g.textPrimary
                    )
                    Text(
                        text = stringResource(
                            R.string.export_selected_summary,
                            selectedCount,
                            totalCategories,
                            FormatUtils.formatBytes(totalEstBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                }

                DestinationCard(
                    iconRes = R.drawable.ic_save,
                    accentColor = g.accentCyan,
                    title = stringResource(R.string.mode_local_file_title),
                    desc = stringResource(R.string.mode_local_file_desc),
                    onClick = { createSafFileLauncher.launch(viewModel.suggestedArchiveFileName()) }
                )

                FlatSectionContainer(containerColor = g.surfaceContainer) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        DestinationRow(
                            iconRes = R.drawable.ic_usb,
                            accentColor = g.verifiedEmerald,
                            title = stringResource(R.string.mode_usb_drive_title),
                            desc = stringResource(R.string.mode_usb_drive_desc),
                            onClick = { usbTreeLauncher.launch(StorageInspector.createOpenUsbTreeIntent(context)) }
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.usbVolumes.forEach { vol ->
                                StatusPill(
                                    text = "${vol.description} · ${FormatUtils.formatBytes(vol.freeBytes)}",
                                    color = g.verifiedEmerald,
                                    showDot = true
                                )
                            }
                            if (StorageInspector.isFat32SingleFileRisk(totalEstBytes)) {
                                Text(
                                    text = stringResource(R.string.usb_fat32_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.warningAmber
                                )
                            }
                            EtgDivider()
                            EtgSwitchRow(
                                title = stringResource(R.string.opt_usb_unpacked_folder),
                                checked = state.exportConfig.usbUnpackedFolderMode,
                                onCheckedChange = { viewModel.setUsbUnpackedFolderMode(it) }
                            )
                        }
                    }
                }

                DestinationCard(
                    iconRes = R.drawable.ic_cable,
                    accentColor = g.warningAmber,
                    title = stringResource(R.string.mode_wired_cable_title),
                    desc = stringResource(R.string.mode_wired_cable_desc),
                    onClick = {
                        showDestinationSheet = false
                        viewModel.launchTransferAsSender(TransferLinkMode.WIRED)
                    }
                )

                DestinationCard(
                    iconRes = R.drawable.ic_wireless,
                    accentColor = g.accentCyan,
                    title = stringResource(R.string.mode_wireless_title),
                    desc = stringResource(R.string.mode_wireless_desc),
                    onClick = {
                        showDestinationSheet = false
                        viewModel.launchTransferAsSender(TransferLinkMode.WIRELESS)
                    }
                )
            }
        }
    }

    if (showWifiQrScanner) {
        QrScannerDialog(
            title = stringResource(R.string.opt_scan_wifi_qr),
            subtitle = "Wi-Fi → Share QR Code",
            onResult = { raw ->
                showWifiQrScanner = false
                viewModel.addWifiFromQr(raw)
            },
            onDismiss = { showWifiQrScanner = false }
        )
    }

    selectionCategory?.let { cat ->
        SelectionSheet(
            title = stringResource(cat.uiMeta().titleRes),
            items = state.catalogs[cat].orEmpty(),
            loading = cat in state.catalogLoading,
            excluded = state.exportConfig.excluded(cat),
            onToggle = { key, included -> viewModel.setItemIncluded(cat, key, included) },
            onSetAll = { keys, included -> viewModel.setAllItemsIncluded(cat, keys, included) },
            onDismiss = { selectionCategory = null },
            onPreview = if (cat == ExportCategory.PHOTOS_VIDEOS) ({ previewFolder = it }) else null,
            showAvatars = cat in SelectionCatalog.WITH_AVATARS
        )
    }

    previewFolder?.let { folder ->
        FolderPreviewDialog(item = folder, onDismiss = { previewFolder = null })
    }

    if (showOtpScanner) {
        QrScannerDialog(
            title = stringResource(R.string.otp_scan_button),
            subtitle = "Google Authenticator → Transfer accounts",
            onResult = { raw ->
                showOtpScanner = false
                if (!viewModel.addAuthenticatorFromQr(raw)) {
                    Toast.makeText(context, invalidOtpMsg, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showOtpScanner = false }
        )
    }

    if (showManualWifiDialog) {
        ManualWifiDialog(
            onAdd = {
                viewModel.addManualWifi(it)
                showManualWifiDialog = false
            },
            onDismiss = { showManualWifiDialog = false }
        )
    }

    previewWifiEntry?.let { entry ->
        WifiQrPreviewDialog(entry = entry, onDismiss = { previewWifiEntry = null })
    }
}

@Composable
private fun CategoryCardRow(
    category: ExportCategory,
    scanInfo: CategoryScanInfo,
    checked: Boolean,
    expanded: Boolean,
    hasOptions: Boolean,
    state: EtgUiState,
    onToggleChecked: () -> Unit,
    onToggleExpand: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit,
    onToggleVideos: (Boolean) -> Unit,
    onToggleApks: (Boolean) -> Unit,
    onAddSafFolder: () -> Unit,
    onRemoveSafFolder: (String) -> Unit,
    onScanWifiQr: () -> Unit,
    onAddManualWifi: () -> Unit,
    onImportWifiXml: () -> Unit,
    onTryRootWifi: () -> Unit,
    onPreviewWifiQr: (WifiNetworkEntry) -> Unit,
    onRemoveWifi: (WifiNetworkEntry) -> Unit,
    onRequestSmsRole: () -> Unit,
    onOpenDefaultApps: () -> Unit,
    onScanOtpQr: () -> Unit,
    onRemoveOtp: (OtpAccount) -> Unit,
    onReadWifiShizuku: () -> Unit,
    onShizukuSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenSelection: () -> Unit,
    onPickFiles: () -> Unit,
    onRemovePicked: (PickedFile) -> Unit,
    onSinusChatExport: () -> Unit
) {
    val g = LocalGrapheneColors.current
    val meta = category.uiMeta()
    val statusColor = when (scanInfo.status) {
        CategoryStatus.READY -> g.verifiedEmerald
        CategoryStatus.NEEDS_PERMISSION -> g.warningAmber
        CategoryStatus.GUIDED -> g.accentCyan
        CategoryStatus.EMPTY -> g.textTertiary
    }
    val cardBorder by animateColorAsState(
        targetValue = when {
            expanded -> g.accentCyan.copy(alpha = 0.45f)
            checked -> g.borderStrong
            else -> g.border
        },
        animationSpec = tween(200),
        label = "catBorder"
    )
    val cardFill by animateColorAsState(
        targetValue = if (checked) g.surface else g.surface.copy(alpha = if (g.isDark) 0.55f else 0.7f),
        animationSpec = tween(200),
        label = "catFill"
    )
    val badgeTint by animateColorAsState(
        targetValue = if (checked) g.accentCyan else g.textTertiary,
        animationSpec = tween(200),
        label = "catBadge"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevronRot"
    )

    FlatSectionContainer(borderColor = cardBorder, containerColor = cardFill) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(start = 4.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EtgCheckbox(checked = checked, onCheckedChange = { onToggleChecked() })
                Spacer(Modifier.width(4.dp))
                IconBadge(
                    iconRes = meta.iconRes,
                    tint = badgeTint,
                    containerSize = 38.dp,
                    iconSize = 19.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(meta.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (checked) g.textPrimary else g.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        StatusDot(color = statusColor, size = 6.dp)
                    }
                    val subtitle = scanInfo.detailSummary.ifBlank { stringResource(meta.descRes) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (scanInfo.estimatedBytes > 0L) {
                            Text(
                                text = "  ·  " + FormatUtils.formatBytes(scanInfo.estimatedBytes),
                                style = NumericStyle,
                                color = if (checked) g.accentCyan else g.textTertiary,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (hasOptions) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (expanded) g.accentContainer else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_down),
                            contentDescription = null,
                            tint = if (expanded) g.accentCyan else g.textTertiary,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(chevronRotation)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                EtgInsetPanel(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    Text(
                        text = stringResource(meta.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )

                    if (category in SelectionCatalog.SELECTABLE && scanInfo.status != CategoryStatus.NEEDS_PERMISSION) {
                        val excludedCount = state.exportConfig.excluded(category).size
                        val total = state.catalogs[category]?.size
                        EtgButton(
                            text = if (total != null) {
                                stringResource(R.string.sel_choose_count, total - excludedCount, total)
                            } else {
                                stringResource(R.string.sel_choose)
                            },
                            onClick = onOpenSelection,
                            iconRes = R.drawable.ic_tune,
                            style = EtgButtonStyle.TONAL,
                            size = EtgButtonSize.SMALL
                        )
                        if (excludedCount > 0) {
                            Text(
                                text = stringResource(R.string.sel_excluded_note, excludedCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = g.warningAmber
                            )
                        }
                    }

                    val sinusCtx = LocalContext.current
                    val sinusSupported = remember(category) {
                        category == ExportCategory.CHAT_BACKUPS && SinusChatBridge.supportsExport(sinusCtx)
                    }
                    if (sinusSupported) {
                        OptionSubheader(
                            iconRes = R.drawable.ic_chat,
                            title = stringResource(R.string.sinus_export_title),
                            desc = stringResource(R.string.sinus_export_desc)
                        )
                        SinusHandoffPanel(
                            phase = state.sinusPhase,
                            progress = state.sinusProgress,
                            received = state.exportConfig.pickedFiles.lastOrNull { SinusChatBridge.isReceivedExport(sinusCtx, it.uri) },
                            receivedAt = state.sinusReceivedAt,
                            withMedia = state.sinusWithMedia,
                            onStart = onSinusChatExport,
                            onRemove = onRemovePicked
                        )
                        EtgDivider()
                    }

                    if (category == ExportCategory.CHAT_BACKUPS || category == ExportCategory.VAULT_KEYS_2FA) {
                        OptionSubheader(
                            iconRes = R.drawable.ic_add,
                            title = stringResource(R.string.pick_title),
                            desc = stringResource(
                                if (category == ExportCategory.CHAT_BACKUPS) R.string.pick_desc_chats else R.string.pick_desc_vault
                            )
                        )
                        EtgButton(
                            text = stringResource(R.string.pick_button),
                            onClick = onPickFiles,
                            iconRes = R.drawable.ic_folder,
                            style = EtgButtonStyle.TONAL,
                            size = EtgButtonSize.SMALL
                        )
                        state.exportConfig.pickedFiles.filter {
                            it.category == category && !(sinusSupported && SinusChatBridge.isReceivedExport(sinusCtx, it.uri))
                        }.forEach { f ->
                            ListItemChip(
                                iconRes = R.drawable.ic_save,
                                title = f.name,
                                subtitle = listOf(f.label, FormatUtils.formatBytes(f.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · ")
                            ) {
                                EtgIconButton(
                                    iconRes = R.drawable.ic_delete,
                                    onClick = { onRemovePicked(f) },
                                    tint = g.errorRed,
                                    size = 30.dp
                                )
                            }
                        }
                        EtgDivider()
                    }

                    if (scanInfo.status == CategoryStatus.NEEDS_PERMISSION) {
                        EtgButton(
                            text = stringResource(R.string.action_grant_permission),
                            onClick = { onRequestPermissions(scanInfo.missingPermissions) },
                            iconRes = R.drawable.ic_shield,
                            tone = EtgTone.WARNING,
                            style = EtgButtonStyle.TONAL,
                            size = EtgButtonSize.SMALL
                        )
                    }

                    when (category) {
                        ExportCategory.MESSAGES_SMS_MMS -> {
                            EtgDivider()
                            OptionSubheader(
                                iconRes = R.drawable.ic_shield,
                                title = stringResource(R.string.blocked_section_title),
                                desc = stringResource(R.string.blocked_section_desc)
                            )
                            if (state.isDefaultSmsApp) {
                                StatusPill(
                                    text = stringResource(R.string.blocked_ready),
                                    color = g.verifiedEmerald,
                                    showDot = true
                                )
                                EtgButton(
                                    text = stringResource(R.string.blocked_restore_default),
                                    onClick = onOpenDefaultApps,
                                    tone = EtgTone.NEUTRAL,
                                    style = EtgButtonStyle.OUTLINE,
                                    size = EtgButtonSize.SMALL
                                )
                            } else {
                                EtgButton(
                                    text = stringResource(R.string.blocked_make_default),
                                    onClick = onRequestSmsRole,
                                    iconRes = R.drawable.ic_sms,
                                    style = EtgButtonStyle.TONAL,
                                    size = EtgButtonSize.SMALL
                                )
                            }
                        }

                        ExportCategory.PHOTOS_VIDEOS -> {
                            EtgSwitchRow(
                                title = stringResource(R.string.opt_include_videos),
                                checked = state.exportConfig.includeVideos,
                                onCheckedChange = onToggleVideos
                            )
                        }

                        ExportCategory.SYSTEM_SETTINGS -> {
                            if (state.shizukuStatus != ShizukuBridge.Status.UNSUPPORTED_ANDROID) {
                                ShizukuWifiBlock(
                                    status = state.shizukuStatus,
                                    busy = ExportCategory.SYSTEM_SETTINGS in state.catalogLoading,
                                    onRead = onShizukuSettings,
                                    onOpenShizuku = onOpenShizuku,
                                    titleRes = R.string.shizuku_settings_title,
                                    descRes = R.string.shizuku_settings_desc,
                                    actionRes = R.string.shizuku_connect_settings,
                                    actionIcon = R.drawable.ic_tune
                                )
                            }
                        }

                        ExportCategory.APPS_AND_APKS -> {
                            EtgSwitchRow(
                                title = stringResource(R.string.opt_include_apks),
                                checked = state.exportConfig.includeApks,
                                onCheckedChange = onToggleApks
                            )
                        }

                        ExportCategory.CHAT_BACKUPS -> {
                            val chats = state.chatApps.filter { it.installed || it.detectedFilesCount > 0 }
                            chats.forEachIndexed { idx, chat ->
                                if (idx > 0) EtgDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = chat.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = g.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    StatusPill(
                                        text = "${chat.detectedFilesCount} · ${FormatUtils.formatBytes(chat.detectedBytes)}",
                                        color = if (chat.detectedFilesCount > 0) g.verifiedEmerald else g.textTertiary
                                    )
                                }
                            }
                            EtgButton(
                                text = stringResource(R.string.opt_add_saf_folder),
                                onClick = onAddSafFolder,
                                iconRes = R.drawable.ic_add,
                                tone = EtgTone.NEUTRAL,
                                style = EtgButtonStyle.OUTLINE,
                                size = EtgButtonSize.SMALL
                            )
                        }

                        ExportCategory.DOCUMENTS_DOWNLOADS,
                        ExportCategory.VAULT_KEYS_2FA -> {
                            if (category == ExportCategory.VAULT_KEYS_2FA) {
                                OptionSubheader(
                                    iconRes = R.drawable.ic_key,
                                    title = stringResource(R.string.otp_section_title),
                                    desc = stringResource(R.string.otp_section_desc)
                                )
                                EtgButton(
                                    text = stringResource(R.string.otp_scan_button),
                                    onClick = onScanOtpQr,
                                    iconRes = R.drawable.ic_qr_scan,
                                    style = EtgButtonStyle.TONAL,
                                    size = EtgButtonSize.SMALL
                                )
                                val otpAccounts = state.exportConfig.authenticatorAccounts
                                if (otpAccounts.isNotEmpty() && !state.exportConfig.encryptArchive) {
                                    Text(
                                        text = stringResource(R.string.otp_unencrypted_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = g.warningAmber
                                    )
                                }
                                otpAccounts.forEach { acc ->
                                    ListItemChip(
                                        iconRes = R.drawable.ic_key,
                                        title = acc.issuer.ifBlank { acc.name },
                                        subtitle = if (acc.issuer.isBlank()) acc.type.uppercase() else acc.name + " · " + acc.type.uppercase()
                                    ) {
                                        EtgIconButton(
                                            iconRes = R.drawable.ic_delete,
                                            onClick = { onRemoveOtp(acc) },
                                            tint = g.errorRed,
                                            size = 30.dp
                                        )
                                    }
                                }
                                EtgDivider()
                            }
                            EtgButton(
                                text = stringResource(R.string.opt_add_saf_folder),
                                onClick = onAddSafFolder,
                                iconRes = R.drawable.ic_add,
                                tone = EtgTone.NEUTRAL,
                                style = EtgButtonStyle.OUTLINE,
                                size = EtgButtonSize.SMALL
                            )
                            state.exportConfig.extraSafFolders.forEach { uriStr ->
                                ListItemChip(
                                    iconRes = R.drawable.ic_folder,
                                    title = uriStr.toUri().lastPathSegment ?: uriStr,
                                    mono = true
                                ) {
                                    EtgIconButton(
                                        iconRes = R.drawable.ic_delete,
                                        onClick = { onRemoveSafFolder(uriStr) },
                                        tint = g.errorRed,
                                        size = 30.dp
                                    )
                                }
                            }
                        }

                        ExportCategory.WIFI_NETWORKS -> {
                            if (state.shizukuStatus != ShizukuBridge.Status.UNSUPPORTED_ANDROID) {
                                ShizukuWifiBlock(
                                    status = state.shizukuStatus,
                                    busy = state.shizukuBusy,
                                    onRead = onReadWifiShizuku,
                                    onOpenShizuku = onOpenShizuku
                                )
                                EtgDivider()
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                EtgButton(
                                    text = stringResource(R.string.opt_scan_wifi_qr),
                                    onClick = onScanWifiQr,
                                    iconRes = R.drawable.ic_qr_scan,
                                    style = EtgButtonStyle.TONAL,
                                    size = EtgButtonSize.SMALL,
                                    modifier = Modifier.weight(1f)
                                )
                                EtgButton(
                                    text = stringResource(R.string.opt_add_wifi_manual),
                                    onClick = onAddManualWifi,
                                    iconRes = R.drawable.ic_add,
                                    tone = EtgTone.NEUTRAL,
                                    style = EtgButtonStyle.OUTLINE,
                                    size = EtgButtonSize.SMALL,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                EtgButton(
                                    text = stringResource(R.string.opt_import_wifi_xml),
                                    onClick = onImportWifiXml,
                                    tone = EtgTone.NEUTRAL,
                                    style = EtgButtonStyle.OUTLINE,
                                    size = EtgButtonSize.SMALL,
                                    modifier = Modifier.weight(1f)
                                )
                                EtgButton(
                                    text = stringResource(R.string.opt_try_root_wifi),
                                    onClick = onTryRootWifi,
                                    tone = EtgTone.NEUTRAL,
                                    style = EtgButtonStyle.OUTLINE,
                                    size = EtgButtonSize.SMALL,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            state.exportConfig.manualWifiEntries.forEach { wifi ->
                                ListItemChip(
                                    iconRes = R.drawable.ic_wifi,
                                    title = wifi.ssid,
                                    subtitle = "${wifi.securityType} · ${wifi.source}"
                                ) {
                                    EtgIconButton(
                                        iconRes = R.drawable.ic_qr,
                                        onClick = { onPreviewWifiQr(wifi) },
                                        tint = g.accentCyan,
                                        size = 30.dp
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    EtgIconButton(
                                        iconRes = R.drawable.ic_delete,
                                        onClick = { onRemoveWifi(wifi) },
                                        tint = g.errorRed,
                                        size = 30.dp
                                    )
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ShizukuWifiBlock(
    status: ShizukuBridge.Status,
    busy: Boolean,
    onRead: () -> Unit,
    onOpenShizuku: () -> Unit,
    titleRes: Int = R.string.shizuku_wifi_title,
    descRes: Int = R.string.shizuku_wifi_desc,
    actionRes: Int = R.string.shizuku_read_wifi,
    actionIcon: Int = R.drawable.ic_wifi
) {
    val g = LocalGrapheneColors.current
    OptionSubheader(
        iconRes = R.drawable.ic_key,
        title = stringResource(titleRes),
        desc = stringResource(descRes)
    )
    val (pillText, pillColor) = when (status) {
        ShizukuBridge.Status.READY -> stringResource(R.string.shizuku_status_ready) to g.verifiedEmerald
        ShizukuBridge.Status.NO_PERMISSION -> stringResource(R.string.shizuku_status_permission) to g.accentCyan
        ShizukuBridge.Status.NOT_RUNNING -> stringResource(R.string.shizuku_status_stopped) to g.warningAmber
        else -> stringResource(R.string.shizuku_status_missing) to g.textTertiary
    }
    StatusPill(text = pillText, color = pillColor, showDot = true)
    when (status) {
        ShizukuBridge.Status.READY, ShizukuBridge.Status.NO_PERMISSION -> EtgButton(
            text = stringResource(if (busy) R.string.shizuku_reading else actionRes),
            onClick = onRead,
            enabled = !busy,
            iconRes = actionIcon,
            style = EtgButtonStyle.TONAL,
            size = EtgButtonSize.SMALL
        )
        ShizukuBridge.Status.NOT_RUNNING -> EtgButton(
            text = stringResource(R.string.shizuku_open),
            onClick = onOpenShizuku,
            tone = EtgTone.WARNING,
            style = EtgButtonStyle.TONAL,
            size = EtgButtonSize.SMALL
        )
        else -> EtgButton(
            text = stringResource(R.string.shizuku_install),
            onClick = onOpenShizuku,
            tone = EtgTone.NEUTRAL,
            style = EtgButtonStyle.OUTLINE,
            size = EtgButtonSize.SMALL
        )
    }
}

@Composable
private fun OptionSubheader(iconRes: Int, title: String, desc: String) {
    val g = LocalGrapheneColors.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = g.accentCyan,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = g.textPrimary)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
        }
    }
}

@Composable
private fun ListItemChip(
    iconRes: Int,
    title: String,
    subtitle: String? = null,
    mono: Boolean = false,
    trailing: @Composable () -> Unit
) {
    val g = LocalGrapheneColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(g.surface)
            .border(1.dp, g.border, shape)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = g.textTertiary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (mono) MonoTechnicalStyle else MaterialTheme.typography.titleSmall,
                color = if (mono) g.textSecondary else g.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, style = MonoTechnicalStyle, color = g.textTertiary, maxLines = 1)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun DestinationCard(
    iconRes: Int,
    accentColor: Color,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    val g = LocalGrapheneColors.current
    FlatSectionContainer(containerColor = g.surfaceContainer) {
        DestinationRow(iconRes, accentColor, title, desc, onClick)
    }
}

@Composable
private fun DestinationRow(
    iconRes: Int,
    accentColor: Color,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    val g = LocalGrapheneColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        IconBadge(iconRes = iconRes, tint = accentColor, containerSize = 44.dp, iconSize = 21.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = g.textPrimary)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = g.textTertiary,
            modifier = Modifier
                .size(18.dp)
                .rotate(-90f)
        )
    }
}
