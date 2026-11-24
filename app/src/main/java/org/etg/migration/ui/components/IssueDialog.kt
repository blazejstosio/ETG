package org.etg.migration.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.etg.migration.R
import org.etg.migration.data.exporter.SelectionCatalog
import org.etg.migration.data.exporter.SettingsCatalog
import org.etg.migration.model.IssuePhase
import org.etg.migration.model.IssueReason
import org.etg.migration.model.TransferIssue
import org.etg.migration.ui.IssueSession
import org.etg.migration.ui.screens.uiMeta
import org.etg.migration.ui.theme.EtgButton
import org.etg.migration.ui.theme.EtgButtonSize
import org.etg.migration.ui.theme.EtgButtonStyle
import org.etg.migration.ui.theme.EtgCheckbox
import org.etg.migration.ui.theme.EtgDialog
import org.etg.migration.ui.theme.EtgDivider
import org.etg.migration.ui.theme.EtgInsetPanel
import org.etg.migration.ui.theme.EtgProgressBar
import org.etg.migration.ui.theme.EtgTone
import org.etg.migration.ui.theme.LocalGrapheneColors
import org.etg.migration.ui.theme.NumericStyle

@Composable
fun IssueDialog(
    session: IssueSession,
    onSkip: (applyToAll: Boolean) -> Unit,
    onRetry: (applyToAll: Boolean) -> Unit,
    onClose: () -> Unit
) {
    if (session.finished) {
        IssueSummary(session, onClose)
        return
    }
    val issue = session.pending.firstOrNull() ?: return
    val g = LocalGrapheneColors.current
    var applyToAll by remember { mutableStateOf(false) }
    val total = session.all.size
    val position = total - session.pending.size + 1

    EtgDialog(onDismissRequest = {}) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                pluralStringResource(
                    if (session.phase == IssuePhase.EXPORT) R.plurals.issue_title_export else R.plurals.issue_title_import,
                    total, total
                ),
                style = MaterialTheme.typography.titleLarge,
                color = g.textPrimary
            )
            Text(
                stringResource(R.string.issue_position, position, total),
                style = NumericStyle,
                color = g.textSecondary
            )
        }

        EtgInsetPanel {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryLine(issue, session)
                Text(issueName(issue), style = MaterialTheme.typography.titleMedium, color = g.textPrimary)
                issueTechnical(issue)?.let {
                    Text("($it)", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = g.textTertiary)
                }
                EtgDivider()
                Text(reasonText(issue), style = MaterialTheme.typography.bodyMedium, color = g.warningAmber)
                if (issue.reasonDetail.isNotBlank() && issue.reason !in REASONS_WITH_INLINE_DETAIL) {
                    Text(issue.reasonDetail, style = MaterialTheme.typography.bodySmall, color = g.textTertiary, maxLines = 3)
                }
                if (issue.attempts > 0) {
                    Text(stringResource(R.string.issue_attempts, issue.attempts), style = MaterialTheme.typography.labelMedium, color = g.textSecondary)
                }
                if (issue.phase == IssuePhase.EXPORT && session.phase == IssuePhase.IMPORT) {
                    Text(stringResource(R.string.issue_from_old_phone), style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
                } else if (!issue.retryable) {
                    Text(stringResource(R.string.issue_not_retryable), style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
                }
            }
        }

        if (session.pending.size > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !session.busy) { applyToAll = !applyToAll }
            ) {
                EtgCheckbox(checked = applyToAll, onCheckedChange = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.issue_apply_all, session.pending.size - 1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.textPrimary
                )
            }
        }

        if (session.busy) {
            EtgProgressBar(fraction = null)
            Text(stringResource(R.string.issue_retrying), style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
        }

        val canRetry = issue.retryable || (applyToAll && session.pending.any { it.retryable })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            EtgButton(
                text = stringResource(R.string.issue_skip),
                onClick = { onSkip(applyToAll) },
                enabled = !session.busy,
                tone = EtgTone.NEUTRAL,
                style = EtgButtonStyle.OUTLINE,
                size = EtgButtonSize.MEDIUM,
                modifier = Modifier.weight(1f)
            )
            EtgButton(
                text = stringResource(R.string.issue_retry),
                onClick = { onRetry(applyToAll) },
                enabled = !session.busy && canRetry,
                size = EtgButtonSize.MEDIUM,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun IssueSummary(session: IssueSession, onClose: () -> Unit) {
    val g = LocalGrapheneColors.current
    EtgDialog(onDismissRequest = onClose) {
        Text(stringResource(R.string.issue_summary_title), style = MaterialTheme.typography.titleLarge, color = g.textPrimary)
        Text(
            stringResource(R.string.issue_summary_counts, session.fixed, session.skipped.size),
            style = MaterialTheme.typography.bodyMedium,
            color = g.textSecondary
        )
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            session.skipped.groupBy { it.category }.forEach { (category, items) ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    CategoryLine(items.first(), session)
                    items.take(6).forEach { issue ->
                        val tech = issueTechnical(issue)
                        Text(
                            "• " + issueName(issue) + (tech?.let { "  ($it)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textPrimary,
                            maxLines = 2
                        )
                    }
                    if (items.size > 6) {
                        Text(stringResource(R.string.issue_and_more, items.size - 6), style = MaterialTheme.typography.bodySmall, color = g.textTertiary)
                    }
                }
            }
        }
        Text(stringResource(R.string.issue_summary_hint), style = MaterialTheme.typography.bodySmall, color = g.textTertiary)
        EtgButton(text = stringResource(R.string.action_ok), onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CategoryLine(issue: TransferIssue, session: IssueSession) {
    val g = LocalGrapheneColors.current
    val category = issue.category
    val failedInCategory = session.all.count { it.category == category }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(category?.uiMeta()?.iconRes ?: R.drawable.ic_folder),
            contentDescription = null,
            tint = g.accentCyan,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (category != null) stringResource(category.uiMeta().titleRes) else stringResource(R.string.issue_archive),
            style = MaterialTheme.typography.labelLarge,
            color = g.accentCyan,
            modifier = Modifier.weight(1f, fill = false)
        )
        val totalInCategory = maxOf(issue.categoryTotal, failedInCategory)
        if (totalInCategory > 0 && issue.reason != IssueReason.PARTIAL_IMPORT) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.issue_category_count, failedInCategory, totalInCategory),
                style = NumericStyle,
                color = g.textSecondary
            )
        }
    }
}

private val REASONS_WITH_INLINE_DETAIL = setOf(IssueReason.APP_NOT_INSTALLED)

@Composable
private fun issueName(issue: TransferIssue): String {
    SettingsCatalog.def(issue.settingId.ifBlank { issue.itemName })?.let { return stringResource(it.labelRes) }
    when (issue.settingId) {
        SelectionCatalog.SETTING_RINGTONE -> return stringResource(R.string.set_ringtone) + " – " + issue.itemName
        SelectionCatalog.SETTING_NOTIFICATION -> return stringResource(R.string.set_notification_sound) + " – " + issue.itemName
        SelectionCatalog.SETTING_ALARM -> return stringResource(R.string.set_alarm_sound) + " – " + issue.itemName
        SelectionCatalog.SETTING_WALLPAPER -> return stringResource(R.string.set_wallpaper)
    }
    return when (issue.reason) {
        IssueReason.PARTIAL_IMPORT -> {
            val n = issue.itemName.toIntOrNull() ?: 0
            pluralStringResource(R.plurals.issue_partial_items, n, n, issue.categoryTotal)
        }
        IssueReason.READ_INTERRUPTED -> stringResource(R.string.issue_read_progress, issue.itemName)
        else -> if (issue.category != null && issue.itemName == issue.category.name) {
            stringResource(R.string.issue_whole_category)
        } else issue.itemName
    }
}

private fun issueTechnical(issue: TransferIssue): String? =
    issue.detail.takeIf { it.isNotBlank() && it != issue.itemName && issue.reason != IssueReason.PARTIAL_IMPORT }

@Composable
private fun reasonText(issue: TransferIssue): String = when (issue.reason) {
    IssueReason.NOT_FOUND -> stringResource(R.string.issue_r_not_found)
    IssueReason.NO_PERMISSION -> stringResource(R.string.issue_r_no_permission)
    IssueReason.IO_ERROR -> stringResource(R.string.issue_r_io)
    IssueReason.CORRUPTED -> stringResource(R.string.issue_r_corrupted)
    IssueReason.READ_INTERRUPTED -> stringResource(R.string.issue_r_interrupted)
    IssueReason.WRITE_FAILED -> stringResource(R.string.issue_r_write)
    IssueReason.APP_NOT_INSTALLED -> stringResource(R.string.issue_r_app_missing, issue.reasonDetail.ifBlank { issue.detail })
    IssueReason.NEEDS_SHIZUKU -> stringResource(R.string.issue_r_shizuku)
    IssueReason.NEEDS_WRITE_SETTINGS -> stringResource(R.string.issue_r_write_settings)
    IssueReason.SOUND_MISSING -> stringResource(R.string.issue_r_sound)
    IssueReason.REFUSED_BY_SYSTEM -> stringResource(R.string.issue_r_refused)
    IssueReason.PARTIAL_IMPORT -> stringResource(R.string.issue_r_partial)
    IssueReason.UNKNOWN -> stringResource(R.string.issue_r_unknown)
}
