package org.etg.migration.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.etg.migration.BuildConfig
import org.etg.migration.R
import org.etg.migration.model.ReadinessSeverity
import org.etg.migration.ui.EtgUiState
import org.etg.migration.ui.EtgViewModel
import org.etg.migration.ui.theme.EtgBanner
import org.etg.migration.ui.theme.EtgButton
import org.etg.migration.ui.theme.EtgButtonSize
import org.etg.migration.ui.theme.EtgButtonStyle
import org.etg.migration.ui.theme.EtgCard
import org.etg.migration.ui.theme.EtgCheckbox
import org.etg.migration.ui.theme.EtgDimens
import org.etg.migration.ui.theme.EtgDivider
import org.etg.migration.ui.theme.EtgLanguagePreference
import org.etg.migration.ui.theme.EtgProgressBar
import org.etg.migration.ui.theme.EtgScreenHeader
import org.etg.migration.ui.theme.EtgTextField
import org.etg.migration.ui.theme.EtgThemePreference
import org.etg.migration.ui.theme.EtgTone
import org.etg.migration.ui.theme.FlatSectionContainer
import org.etg.migration.ui.theme.GrapheneHeroCard
import org.etg.migration.ui.theme.GrapheneLogoMark
import org.etg.migration.ui.theme.LocalGrapheneColors
import org.etg.migration.ui.theme.MonoTechnicalStyle
import org.etg.migration.ui.theme.NumericStyle
import org.etg.migration.ui.theme.SectionHeader
import org.etg.migration.ui.theme.SegmentedPillSelector
import org.etg.migration.ui.theme.StatusPill

private data class ChecklistStep(
    val id: String,
    val titlePl: String,
    val titleEn: String,
    val descPl: String,
    val descEn: String
)

private val MIGRATION_CHECKLIST = listOf(
    ChecklistStep(
        id = "step_2fa",
        titlePl = "Wyeksportuj tokeny 2FA i hasła",
        titleEn = "Export 2FA tokens & passwords",
        descPl = "Przenieś kody z Google Authenticator/Authy (polecany Aegis lub Ente Auth) oraz bazę KeePass.",
        descEn = "Export secrets from Google Authenticator/Authy (recommended: Aegis or Ente Auth) and KeePass vaults."
    ),
    ChecklistStep(
        id = "step_chats",
        titlePl = "Zrób lokalną kopię Signal i WhatsApp",
        titleEn = "Create local Signal & WhatsApp backups",
        descPl = "Zapisz 30-cyfrowy klucz Signal oraz wykonaj lokalny backup WhatsApp (bez Google Drive).",
        descEn = "Save Signal's 30-digit passphrase and trigger a local WhatsApp backup (not Google Drive)."
    ),
    ChecklistStep(
        id = "step_wifi",
        titlePl = "Zapisz ważne sieci Wi-Fi w ETG",
        titleEn = "Queue important Wi-Fi networks in ETG",
        descPl = "Zeskanuj kody QR Udostępnij Wi-Fi z ustawień systemu lub wczytaj WifiConfigStore.xml.",
        descEn = "Scan system Wi-Fi Share QR codes or import WifiConfigStore.xml before wiping."
    ),
    ChecklistStep(
        id = "step_esim",
        titlePl = "Sprawdź karty eSIM i kody PUK",
        titleEn = "Backup eSIM QR & carrier info",
        descPl = "Odblokowanie bootloadera czyści dane użytkownika — upewnij się, że masz kod QR aktywacji eSIM.",
        descEn = "Unlocking the bootloader wipes userdata — keep your carrier eSIM activation QR ready."
    ),
    ChecklistStep(
        id = "step_etg_export",
        titlePl = "Wykonaj eksport ETG poza telefon",
        titleEn = "Export ETG backup off the device",
        descPl = "Zapisz zaszyfrowane archiwum .etg na pendrive USB-OTG, komputer (ADB/HTTP) lub drugi telefon!",
        descEn = "Save your encrypted .etg archive onto a USB-OTG drive, PC (ADB/HTTP), or second device BEFORE flashing!"
    ),
    ChecklistStep(
        id = "step_flash",
        titlePl = "Zainstaluj GrapheneOS i zablokuj bootloader",
        titleEn = "Flash GrapheneOS & re-lock bootloader",
        descPl = "Użyj oficjalnego instalatora grapheneos.org/install/web i zablokuj bootloader na końcu.",
        descEn = "Use the official web installer at grapheneos.org/install/web and lock the bootloader."
    ),
    ChecklistStep(
        id = "step_restore",
        titlePl = "Zainstaluj ETG na GrapheneOS i przywróć dane",
        titleEn = "Install ETG on GrapheneOS & restore",
        descPl = "Zaimportuj kontakty, kalendarz, SMS, multimedia, sieci Wi-Fi oraz aplikacje (F-Droid / Aurora / Sandboxed Play).",
        descEn = "Restore contacts, calendar, SMS, media, Wi-Fi networks, and apps (F-Droid / Aurora / Sandboxed Play)."
    )
)

private const val AUTHOR_GITHUB_URL = "https://github.com/blazejstosio"
private const val GRAPHENE_WEB_INSTALLER_URL = "https://grapheneos.org/install/web"

private val PRE_INSTALL_STEP_IDS = listOf("step_2fa", "step_chats", "step_wifi", "step_esim", "step_etg_export")

@Composable
fun GuideAndSettingsScreen(
    state: EtgUiState,
    viewModel: EtgViewModel
) {
    val config = LocalConfiguration.current
    val context = LocalContext.current
    val g = LocalGrapheneColors.current
    val isPl = remember(state.langPref, config) {
        when (state.langPref) {
            EtgLanguagePreference.PL -> true
            EtgLanguagePreference.EN -> false
            EtgLanguagePreference.SYSTEM -> config.locales[0].language == "pl"
        }
    }
    var portInput by remember(state.transferPort) { mutableStateOf(state.transferPort.toString()) }

    val completedCount = state.checklistCompletedIds.count { id -> MIGRATION_CHECKLIST.any { it.id == id } }
    val allDone = completedCount == MIGRATION_CHECKLIST.size

    val readyToInstall = PRE_INSTALL_STEP_IDS.all { it in state.checklistCompletedIds }
    val checklistFraction = completedCount.toFloat() / MIGRATION_CHECKLIST.size.toFloat()

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
                eyebrow = stringResource(R.string.screen_guide_eyebrow),
                title = stringResource(R.string.screen_guide_title),
                subtitle = stringResource(R.string.screen_guide_subtitle)
            )
        }

        item(key = "progress_hero") {
            GrapheneHeroCard(accentColor = if (allDone || readyToInstall) g.verifiedEmerald else g.accentCyan) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.guide_checklist_title).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = g.textTertiary
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$completedCount",
                            style = MaterialTheme.typography.displaySmall,
                            color = if (allDone) g.verifiedEmerald else g.textPrimary
                        )
                        Text(
                            text = " / ${MIGRATION_CHECKLIST.size}",
                            style = MaterialTheme.typography.titleLarge,
                            color = g.textTertiary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    EtgProgressBar(
                        fraction = checklistFraction,
                        tone = if (allDone) EtgTone.SUCCESS else EtgTone.ACCENT
                    )
                    Text(
                        text = stringResource(R.string.guide_progress_label, completedCount, MIGRATION_CHECKLIST.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                }
            }
        }

        if (allDone || readyToInstall) {
            item(key = "ready_banner") {
                EtgBanner(
                    tone = EtgTone.SUCCESS,
                    iconRes = R.drawable.ic_shield,
                    title = stringResource(if (allDone) R.string.guide_done_title else R.string.guide_ready_title),
                    text = stringResource(if (allDone) R.string.guide_done_desc else R.string.guide_ready_desc)
                ) {
                    if (!allDone) {
                        EtgButton(
                            text = stringResource(R.string.guide_ready_open_installer),
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, GRAPHENE_WEB_INSTALLER_URL.toUri()))
                                }
                            },
                            tone = EtgTone.SUCCESS,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item(key = "checklist_card") {
            FlatSectionContainer {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MIGRATION_CHECKLIST.forEachIndexed { idx, step ->
                        val checked = step.id in state.checklistCompletedIds
                        val titleColor by animateColorAsState(
                            targetValue = if (checked) g.textTertiary else g.textPrimary,
                            animationSpec = tween(200),
                            label = "stepTitle"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleChecklistItem(step.id) }
                                .padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            StepNumber(index = idx + 1, done = checked)
                            Spacer(Modifier.width(14.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = if (isPl) step.titlePl else step.titleEn,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = titleColor,
                                    textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None
                                )
                                Text(
                                    text = if (isPl) step.descPl else step.descEn,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (checked) g.textTertiary else g.textSecondary
                                )
                            }
                            EtgCheckbox(
                                checked = checked,
                                onCheckedChange = { viewModel.toggleChecklistItem(step.id) },
                                tone = EtgTone.SUCCESS
                            )
                        }
                        if (idx < MIGRATION_CHECKLIST.lastIndex) {
                            EtgDivider(startIndent = 58.dp)
                        }
                    }
                }
            }
        }

        item(key = "audit_header") {
            SectionHeader(
                title = stringResource(R.string.guide_audit_title),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        item(key = "audit_card") {
            FlatSectionContainer {
                Column(modifier = Modifier.fillMaxWidth()) {
                    state.readinessItems.forEachIndexed { idx, item ->
                        val (sevLabel, sevColor) = when (item.severity) {
                            ReadinessSeverity.CRITICAL -> "CRITICAL" to g.errorRed
                            ReadinessSeverity.WARN -> "WARN" to g.warningAmber
                            ReadinessSeverity.INFO -> "INFO" to g.accentCyan
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(8.dp)
                                    .background(sevColor, CircleShape)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = g.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    StatusPill(text = sevLabel, color = sevColor)
                                }
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textSecondary
                                )
                            }
                        }
                        if (idx < state.readinessItems.lastIndex) {
                            EtgDivider(startIndent = 38.dp)
                        }
                    }
                }
            }
        }

        item(key = "appearance_header") {
            SectionHeader(
                title = stringResource(R.string.settings_appearance_title),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        item(key = "appearance_card") {
            EtgCard {
                SegmentedPillSelector(
                    options = listOf(
                        EtgThemePreference.DARK to stringResource(R.string.settings_theme_dark),
                        EtgThemePreference.LIGHT to stringResource(R.string.settings_theme_light),
                        EtgThemePreference.SYSTEM to stringResource(R.string.settings_theme_system)
                    ),
                    selected = state.themePref,
                    onSelect = { viewModel.setThemePref(it) }
                )

                SegmentedPillSelector(
                    options = listOf(
                        EtgLanguagePreference.PL to stringResource(R.string.settings_lang_pl),
                        EtgLanguagePreference.EN to stringResource(R.string.settings_lang_en),
                        EtgLanguagePreference.SYSTEM to stringResource(R.string.settings_theme_system)
                    ),
                    selected = state.langPref,
                    onSelect = { viewModel.setLangPref(it) }
                )

                EtgDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    EtgTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = stringResource(R.string.settings_network_port),
                        keyboardType = KeyboardType.Number,
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    EtgButton(
                        text = stringResource(R.string.action_apply),
                        onClick = { portInput.toIntOrNull()?.let { viewModel.setTransferPort(it) } },
                        enabled = portInput.toIntOrNull() != null && portInput.toIntOrNull() != state.transferPort,
                        style = EtgButtonStyle.TONAL,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        item(key = "about_card") {
            EtgCard(
                containerColor = g.surfaceContainer,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    GrapheneLogoMark(iconSize = 40.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.app_full_name),
                                style = MaterialTheme.typography.titleMedium,
                                color = g.textPrimary
                            )
                            StatusPill(text = "v${BuildConfig.VERSION_NAME}", color = g.accentCyan)
                        }
                        Text(
                            text = stringResource(R.string.about_author),
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_about_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.textPrimary
                )
                Text(
                    text = stringResource(R.string.about_technical),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
                EtgButton(
                    text = stringResource(R.string.about_github),
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, AUTHOR_GITHUB_URL.toUri()))
                        }
                    },
                    style = EtgButtonStyle.TONAL,
                    size = EtgButtonSize.SMALL,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun StepNumber(index: Int, done: Boolean) {
    val g = LocalGrapheneColors.current
    val bg by animateColorAsState(
        targetValue = if (done) g.verifiedEmerald.copy(alpha = 0.16f) else g.surfaceHigh,
        animationSpec = tween(200),
        label = "stepBg"
    )
    val fg by animateColorAsState(
        targetValue = if (done) g.verifiedEmerald else g.textSecondary,
        animationSpec = tween(200),
        label = "stepFg"
    )
    Box(
        modifier = Modifier
            .padding(top = 1.dp)
            .size(28.dp)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "$index", style = NumericStyle, color = fg)
    }
}
