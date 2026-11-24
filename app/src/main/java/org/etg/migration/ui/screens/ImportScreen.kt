package org.etg.migration.ui.screens

import org.etg.migration.data.integration.SinusChatBridge
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.etg.migration.R
import org.etg.migration.data.PermissionHelper
import org.etg.migration.data.exporter.WifiExporter
import org.etg.migration.data.storage.StorageInspector
import org.etg.migration.model.WifiNetworkEntry
import org.etg.migration.ui.EtgUiState
import org.etg.migration.ui.EtgViewModel
import org.etg.migration.ui.TransferLinkMode
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
import org.etg.migration.ui.theme.EtgPasswordField
import org.etg.migration.ui.theme.EtgScreenHeader
import org.etg.migration.ui.theme.EtgStatTile
import org.etg.migration.ui.theme.EtgTone
import org.etg.migration.ui.theme.FlatSectionContainer
import org.etg.migration.ui.theme.FormatUtils
import org.etg.migration.ui.theme.GrapheneHeroCard
import org.etg.migration.ui.theme.IconBadge
import org.etg.migration.ui.theme.LocalGrapheneColors
import org.etg.migration.ui.theme.MonoTechnicalStyle
import org.etg.migration.ui.theme.NumericStyle
import org.etg.migration.ui.theme.SectionHeader
import org.etg.migration.ui.theme.StatusPill

@Composable
fun ImportScreen(
    state: EtgUiState,
    viewModel: EtgViewModel
) {
    val context = LocalContext.current
    val g = LocalGrapheneColors.current
    var qrPreviewWifi by remember { mutableStateOf<WifiNetworkEntry?>(null) }

    val openArchiveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.openImportSafFile(uri)
    }

    val openUsbFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) viewModel.openImportUsbFolder(uri)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemCapabilities()
    }

    val smsRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshSystemCapabilities()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = EtgDimens.screenGutter,
            end = EtgDimens.screenGutter,
            top = 8.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "screen_header") {
            EtgScreenHeader(
                eyebrow = stringResource(R.string.screen_import_eyebrow),
                title = stringResource(R.string.screen_import_title)
            )
        }

        item(key = "import_hero") {
            GrapheneHeroCard(accentColor = g.verifiedEmerald) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatusPill(text = "SHA-256 · AES-256-GCM", color = g.verifiedEmerald, showDot = true)
                    Text(
                        text = stringResource(R.string.import_hero_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = g.textPrimary
                    )
                    Text(
                        text = stringResource(R.string.import_hero_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                }
            }
        }

        item(key = "sources_header") {
            SectionHeader(
                title = stringResource(R.string.import_sources_section),
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        item(key = "sources_grid") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImportSourceTile(
                        iconRes = R.drawable.ic_folder,
                        accent = g.accentCyan,
                        title = stringResource(R.string.import_source_file),
                        onClick = { openArchiveFileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    ImportSourceTile(
                        iconRes = R.drawable.ic_usb,
                        accent = g.verifiedEmerald,
                        title = stringResource(R.string.import_source_usb),
                        onClick = { openUsbFolderLauncher.launch(StorageInspector.createOpenUsbTreeIntent(context)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImportSourceTile(
                        iconRes = R.drawable.ic_cable,
                        accent = g.warningAmber,
                        title = stringResource(R.string.import_source_wired),
                        onClick = { viewModel.launchTransferAsReceiver(TransferLinkMode.WIRED) },
                        modifier = Modifier.weight(1f)
                    )
                    ImportSourceTile(
                        iconRes = R.drawable.ic_wireless,
                        accent = g.accentCyan,
                        title = stringResource(R.string.import_source_wireless),
                        onClick = { viewModel.launchTransferAsReceiver(TransferLinkMode.WIRELESS) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        val preview = state.importPreview
        if (preview != null) {
            if (preview.isEncrypted && !preview.isUnlocked) {
                item(key = "decrypt_card") {
                    val wrongPassword = preview.errorMessage == "WRONG_PASSWORD"
                    EtgCard(borderColor = g.warningAmber.copy(alpha = 0.45f)) {
                        EtgCardHeader(
                            iconRes = R.drawable.ic_lock,
                            tint = g.warningAmber,
                            title = preview.sourceLabel,
                            subtitle = "AES-256-GCM",
                            subtitleMono = true,
                            subtitleColor = g.warningAmber
                        )
                        Text(
                            text = stringResource(R.string.import_decrypt_prompt),
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                        if (wrongPassword) {
                            Text(
                                text = stringResource(R.string.import_wrong_password),
                                style = MaterialTheme.typography.bodySmall,
                                color = g.errorRed
                            )
                        } else if (!preview.errorMessage.isNullOrBlank()) {
                            Text(
                                text = preview.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = g.errorRed
                            )
                        }
                        EtgPasswordField(
                            value = state.importPassword,
                            onValueChange = { viewModel.setImportPassword(it) },
                            label = stringResource(R.string.export_password_label),
                            isError = wrongPassword
                        )
                        EtgButton(
                            text = stringResource(R.string.import_decrypt_button),
                            onClick = { viewModel.unlockImportArchive() },
                            enabled = state.importPassword.isNotBlank(),
                            iconRes = R.drawable.ic_key,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else if (!preview.isUnlocked && !preview.errorMessage.isNullOrBlank()) {
                item(key = "preview_error") {
                    EtgBanner(
                        tone = EtgTone.DANGER,
                        iconRes = R.drawable.ic_shield,
                        text = preview.errorMessage
                    )
                }
            } else if (preview.isUnlocked) {
                item(key = "archive_meta") {
                    EtgCard(borderColor = g.verifiedEmerald.copy(alpha = 0.35f)) {
                        EtgCardHeader(
                            iconRes = R.drawable.ic_save,
                            tint = g.verifiedEmerald,
                            title = preview.sourceLabel,
                            subtitle = preview.manifest?.let { m ->
                                "${m.sourceManufacturer} ${m.sourceDeviceModel} · Android ${m.sourceAndroidVersion}"
                            },
                            subtitleMono = true,
                            trailing = {
                                StatusPill(
                                    text = if (preview.isEncrypted) "AES-256" else "ZIP",
                                    color = g.verifiedEmerald,
                                    showDot = true
                                )
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            EtgStatTile(
                                value = "${preview.archiveEntriesCount}",
                                caption = stringResource(R.string.stat_entries),
                                modifier = Modifier.weight(1f)
                            )
                            EtgStatTile(
                                value = FormatUtils.formatBytes(preview.totalUncompressedBytes),
                                caption = stringResource(R.string.stat_size),
                                valueColor = g.accentCyan,
                                modifier = Modifier.weight(1f)
                            )
                            EtgStatTile(
                                value = "${preview.availableCategories.size}",
                                caption = stringResource(R.string.stat_categories),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        EtgButton(
                            text = stringResource(R.string.action_grant_permission),
                            onClick = { permLauncher.launch(PermissionHelper.allCoreRuntimePermissions()) },
                            iconRes = R.drawable.ic_shield,
                            tone = EtgTone.NEUTRAL,
                            style = EtgButtonStyle.OUTLINE,
                            size = EtgButtonSize.SMALL
                        )
                    }
                }

                item(key = "archive_contents_header") {
                    SectionHeader(
                        title = stringResource(R.string.import_archive_contents),
                        modifier = Modifier.padding(top = 10.dp),
                        trailing = {
                            Text(
                                text = "${state.importSelectedCategories.size} / ${preview.availableCategories.size}",
                                style = NumericStyle,
                                color = g.textTertiary
                            )
                        }
                    )
                }

                item(key = "archive_categories_card") {
                    val categories = preview.availableCategories.toList()
                    FlatSectionContainer {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            categories.forEachIndexed { idx, cat ->
                                val meta = cat.uiMeta()
                                val checked = cat in state.importSelectedCategories
                                val count = preview.manifest?.summaryCounts?.get(cat.name) ?: 0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.toggleImportCategory(cat) }
                                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    EtgCheckbox(
                                        checked = checked,
                                        onCheckedChange = { viewModel.toggleImportCategory(cat) },
                                        tone = EtgTone.SUCCESS
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    IconBadge(
                                        iconRes = meta.iconRes,
                                        tint = if (checked) g.verifiedEmerald else g.textTertiary,
                                        containerSize = 36.dp,
                                        iconSize = 18.dp
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(meta.titleRes),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (checked) g.textPrimary else g.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (count > 0) {
                                        Spacer(Modifier.width(8.dp))
                                        StatusPill(text = "$count", color = if (checked) g.verifiedEmerald else g.textTertiary)
                                    }
                                }
                                if (idx < categories.lastIndex) {
                                    EtgDivider(startIndent = 92.dp)
                                }
                            }
                        }
                    }
                }

                item(key = "restore_cta") {
                    EtgButton(
                        text = stringResource(R.string.import_restore_selected, state.importSelectedCategories.size),
                        onClick = { viewModel.runRestore() },
                        enabled = state.importSelectedCategories.isNotEmpty(),
                        iconRes = R.drawable.ic_download,
                        tone = EtgTone.SUCCESS,
                        size = EtgButtonSize.LARGE,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }

        val result = state.importResult
        if (result != null) {
            item(key = "result_summary") {
                EtgCard(borderColor = g.verifiedEmerald.copy(alpha = 0.40f)) {
                    EtgCardHeader(
                        iconRes = R.drawable.ic_shield,
                        tint = g.verifiedEmerald,
                        title = stringResource(R.string.import_post_actions_title)
                    )
                    result.categoryStats.forEachIndexed { idx, stat ->
                        if (idx > 0) EtgDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(stat.category.uiMeta().titleRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = g.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusPill(text = "+${stat.importedCount}", color = g.verifiedEmerald)
                                    if (stat.skippedDuplicates > 0) {
                                        StatusPill(text = "skip ${stat.skippedDuplicates}", color = g.textSecondary)
                                    }
                                    if (stat.failedCount > 0) {
                                        StatusPill(text = "err ${stat.failedCount}", color = g.errorRed)
                                    }
                                }
                            }
                            if (stat.notes.isNotBlank()) {
                                Text(stat.notes, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
                            }
                        }
                    }
                }
            }

            item(key = "result_sinuschat") {
                val backups = remember(result, state.issueSession) { SinusChatBridge.restoredBackups(context) }
                if (backups.isNotEmpty()) {
                    val installed = SinusChatBridge.isInstalled(context)
                    val notInstalledMsg = stringResource(R.string.sinus_restore_not_installed)
                    EtgCard {
                        EtgCardHeader(
                            iconRes = R.drawable.ic_chat,
                            tint = g.accentCyan,
                            title = stringResource(R.string.sinus_restore_title)
                        )
                        Text(
                            text = stringResource(if (installed) R.string.sinus_restore_desc else R.string.sinus_restore_not_installed),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (installed) g.textSecondary else g.warningAmber
                        )
                        backups.forEach { file ->
                            EtgButton(
                                text = stringResource(R.string.sinus_restore_button),
                                onClick = {
                                    if (!SinusChatBridge.isInstalled(context)) {
                                        android.widget.Toast.makeText(context, notInstalledMsg, android.widget.Toast.LENGTH_LONG).show()
                                    } else {
                                        runCatching { context.startActivity(SinusChatBridge.restoreIntent(context, file)) }
                                    }
                                },
                                iconRes = R.drawable.ic_chat,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(file.name, style = MonoTechnicalStyle, color = g.textTertiary)
                        }
                    }
                }
            }

            if (result.wifiNetworksToSave.isNotEmpty()) {
                item(key = "result_wifi") {
                    EtgCard {
                        EtgCardHeader(
                            iconRes = R.drawable.ic_wifi,
                            tint = g.accentCyan,
                            title = stringResource(R.string.cat_wifi_title),
                            trailing = {
                                StatusPill(text = "${result.wifiNetworksToSave.size}", color = g.accentCyan)
                            }
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            EtgButton(
                                text = stringResource(R.string.restore_wifi_action, result.wifiNetworksToSave.size),
                                onClick = {
                                    val intent = WifiExporter.buildSaveNetworksIntent(result.wifiNetworksToSave)
                                    if (intent != null) context.startActivity(intent)
                                },
                                iconRes = R.drawable.ic_wifi,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        result.wifiNetworksToSave.forEachIndexed { idx, net ->
                            if (idx > 0) EtgDivider()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(net.ssid, style = MaterialTheme.typography.titleSmall, color = g.textPrimary)
                                    Text(net.securityType.name, style = MonoTechnicalStyle, color = g.textTertiary)
                                }
                                EtgIconButton(
                                    iconRes = R.drawable.ic_qr,
                                    onClick = { qrPreviewWifi = net },
                                    tint = g.accentCyan,
                                    size = 34.dp
                                )
                            }
                        }
                    }
                }
            }

            if (result.needsSmsRoleForRestore) {
                item(key = "result_sms_role") {
                    EtgBanner(
                        tone = EtgTone.WARNING,
                        iconRes = R.drawable.ic_sms,
                        text = stringResource(R.string.restore_sms_role_note)
                    ) {
                        EtgButton(
                            text = stringResource(R.string.restore_sms_role_action),
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val rm = context.getSystemService(RoleManager::class.java)
                                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                                        smsRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                                    }
                                } else {
                                    val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                                        putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                                    }
                                    smsRoleLauncher.launch(intent)
                                }
                            },
                            tone = EtgTone.WARNING,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (state.isDefaultSmsApp) {
                            EtgButton(
                                text = stringResource(R.string.import_restore_selected, 1),
                                onClick = { viewModel.runRestore() },
                                tone = EtgTone.SUCCESS,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            val snap = result.settingsSnapshot
            if (snap != null && (snap.systemAuditItems.isNotEmpty() || !state.canWriteSettings)) {
                item(key = "result_settings") {
                    EtgCard {
                        EtgCardHeader(
                            iconRes = R.drawable.ic_tune,
                            tint = g.accentCyan,
                            title = stringResource(R.string.cat_settings_title)
                        )
                        if (!state.canWriteSettings) {
                            EtgButton(
                                text = stringResource(R.string.restore_write_settings_action),
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                                data = "package:${context.packageName}".toUri()
                                            }
                                        )
                                    }
                                },
                                tone = EtgTone.WARNING,
                                style = EtgButtonStyle.TONAL,
                                size = EtgButtonSize.SMALL
                            )
                        }
                        snap.systemAuditItems.forEach { item ->
                            EtgDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = g.textSecondary
                                    )
                                    Text(
                                        text = item.value,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = g.textPrimary
                                    )
                                    if (item.categoryHint.isNotBlank()) {
                                        Text(item.categoryHint, style = MonoTechnicalStyle, color = g.textTertiary)
                                    }
                                }
                                if (item.settingsAction.isNotBlank()) {
                                    EtgButton(
                                        text = stringResource(R.string.restore_open_settings),
                                        onClick = { runCatching { context.startActivity(Intent(item.settingsAction)) } },
                                        tone = EtgTone.NEUTRAL,
                                        style = EtgButtonStyle.OUTLINE,
                                        size = EtgButtonSize.SMALL
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (result.extractedApkEntries.isNotEmpty()) {
                item(key = "result_apks") {
                    EtgCard {
                        EtgCardHeader(
                            iconRes = R.drawable.ic_apps,
                            tint = g.accentCyan,
                            title = stringResource(R.string.cat_apps_title),
                            trailing = {
                                StatusPill(text = "${result.extractedApkEntries.size}", color = g.accentCyan)
                            }
                        )
                        result.extractedApkEntries.forEach { app ->
                            val hasApks = viewModel.hasExtractedApksForPackage(app.packageName)
                            EtgDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(app.label, style = MaterialTheme.typography.titleSmall, color = g.textPrimary)
                                    Text(
                                        "${app.packageName} · v${app.versionName}",
                                        style = MonoTechnicalStyle,
                                        color = g.textTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (app.openSourceAlternatives.isNotBlank()) {
                                        Text(
                                            "FOSS: ${app.openSourceAlternatives}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = g.verifiedEmerald
                                        )
                                    }
                                }
                                if (hasApks) {
                                    EtgButton(
                                        text = stringResource(R.string.restore_install_apk),
                                        onClick = {
                                            if (!PermissionHelper.canInstallPackages(context)) {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                                            data = "package:${context.packageName}".toUri()
                                                        }
                                                    )
                                                }
                                            } else {
                                                viewModel.installExtractedApk(app.packageName)
                                            }
                                        },
                                        style = EtgButtonStyle.TONAL,
                                        size = EtgButtonSize.SMALL
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    qrPreviewWifi?.let { entry ->
        WifiQrPreviewDialog(entry = entry, onDismiss = { qrPreviewWifi = null })
    }
}

@Composable
private fun ImportSourceTile(
    iconRes: Int,
    accent: Color,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = LocalGrapheneColors.current
    FlatSectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            IconBadge(iconRes = iconRes, tint = accent, containerSize = 44.dp, iconSize = 22.dp)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = g.textPrimary,
                maxLines = 3,
                minLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
