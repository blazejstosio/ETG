package org.etg.migration.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.etg.migration.R
import org.etg.migration.codec.WifiQrAndXmlParser
import org.etg.migration.model.OperationPhase
import org.etg.migration.model.OperationProgress
import org.etg.migration.model.WifiNetworkEntry
import org.etg.migration.model.WifiSecurityType
import org.etg.migration.ui.screens.uiMeta
import org.etg.migration.ui.theme.EtgButton
import org.etg.migration.ui.theme.EtgButtonStyle
import org.etg.migration.ui.theme.EtgCardHeader
import org.etg.migration.ui.theme.EtgCheckbox
import org.etg.migration.ui.theme.EtgDialog
import org.etg.migration.ui.theme.EtgDimens
import org.etg.migration.ui.theme.EtgPasswordField
import org.etg.migration.ui.theme.EtgProgressBar
import org.etg.migration.ui.theme.EtgTextField
import org.etg.migration.ui.theme.EtgTone
import org.etg.migration.ui.theme.FormatUtils
import org.etg.migration.ui.theme.IconBadge
import org.etg.migration.ui.theme.LocalGrapheneColors
import org.etg.migration.ui.theme.MonoTechnicalStyle
import org.etg.migration.ui.theme.NumericStyle
import org.etg.migration.ui.theme.QrCodeImage
import org.etg.migration.ui.theme.SegmentedPillSelector
import org.etg.migration.ui.theme.StatusPill

@Composable
fun OperationProgressDialog(
    progress: OperationProgress,
    onCancel: () -> Unit
) {
    if (progress.phase != OperationPhase.EXPORTING &&
        progress.phase != OperationPhase.IMPORTING &&
        progress.phase != OperationPhase.TRANSFERRING &&
        progress.phase != OperationPhase.INSPECTING
    ) return

    val g = LocalGrapheneColors.current
    val phaseLabel = when (progress.phase) {
        OperationPhase.EXPORTING -> "EXPORTING"
        OperationPhase.IMPORTING -> "RESTORING"
        OperationPhase.TRANSFERRING -> "TRANSFERRING"
        else -> "INSPECTING"
    }
    val phaseIcon = when (progress.phase) {
        OperationPhase.EXPORTING -> R.drawable.ic_upload
        OperationPhase.IMPORTING -> R.drawable.ic_download
        OperationPhase.TRANSFERRING -> R.drawable.ic_swap
        else -> R.drawable.ic_shield
    }
    val phaseTone = if (progress.phase == OperationPhase.IMPORTING) EtgTone.SUCCESS else EtgTone.ACCENT
    val phaseColor = if (phaseTone == EtgTone.SUCCESS) g.verifiedEmerald else g.accentCyan

    val fraction = when {
        progress.totalBytes > 0L && progress.processedBytes > 0L ->
            (progress.processedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
        progress.totalItems > 0 ->
            (progress.processedItems.toFloat() / progress.totalItems.toFloat()).coerceIn(0f, 1f)
        else -> null
    }

    EtgDialog(onDismissRequest = {}) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(iconRes = phaseIcon, tint = phaseColor, containerSize = 44.dp, iconSize = 21.dp)
            Spacer(Modifier.width(12.dp))
            StatusPill(text = phaseLabel, color = phaseColor, showDot = true)
            Spacer(Modifier.weight(1f))
            if (fraction != null) {
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                    color = g.textPrimary
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val categoryTitle = progress.currentCategory?.let { stringResource(it.uiMeta().titleRes) }
            Text(
                text = categoryTitle ?: progress.currentItemLabel,
                style = MaterialTheme.typography.titleMedium,
                color = g.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (progress.currentItemLabel.isNotBlank() && categoryTitle != null) {
                Text(
                    text = progress.currentItemLabel,
                    style = MonoTechnicalStyle,
                    color = g.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        EtgProgressBar(fraction = fraction, tone = phaseTone, height = 8.dp)

        val bytesStr = if (progress.totalBytes > 0L) {
            "${FormatUtils.formatBytes(progress.processedBytes)} / ${FormatUtils.formatBytes(progress.totalBytes)}"
        } else if (progress.totalItems > 0) {
            "${progress.processedItems} / ${progress.totalItems}"
        } else ""

        val speedStr = buildString {
            if (progress.speedBytesPerSec > 0L) {
                append(FormatUtils.formatSpeed(progress.speedBytesPerSec))
            }
            if (progress.etaSeconds >= 0L) {
                if (isNotEmpty()) append(" · ETA ")
                append(FormatUtils.formatEta(progress.etaSeconds))
            }
        }

        if (bytesStr.isNotBlank() || speedStr.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = bytesStr, style = NumericStyle, color = g.textSecondary)
                Text(text = speedStr, style = NumericStyle, color = phaseColor)
            }
        }

        EtgButton(
            text = stringResource(R.string.action_cancel),
            onClick = onCancel,
            tone = EtgTone.DANGER,
            style = EtgButtonStyle.TONAL,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun ManualWifiDialog(
    onAdd: (WifiNetworkEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val g = LocalGrapheneColors.current
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secType by remember { mutableStateOf(WifiSecurityType.WPA2) }
    var hidden by remember { mutableStateOf(false) }

    EtgDialog(onDismissRequest = onDismiss) {
        EtgCardHeader(
            iconRes = R.drawable.ic_wifi,
            tint = g.accentCyan,
            title = stringResource(R.string.opt_add_wifi_manual)
        )

        EtgTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = "SSID",
            modifier = Modifier.fillMaxWidth()
        )

        SegmentedPillSelector(
            options = WifiSecurityType.entries.map { it to it.name },
            selected = secType,
            onSelect = { secType = it }
        )

        if (secType != WifiSecurityType.OPEN) {
            EtgPasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.export_password_label)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { hidden = !hidden },
            verticalAlignment = Alignment.CenterVertically
        ) {
            EtgCheckbox(checked = hidden, onCheckedChange = null)
            Text("Hidden SSID", color = g.textPrimary, style = MaterialTheme.typography.bodyMedium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            EtgButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                tone = EtgTone.NEUTRAL,
                style = EtgButtonStyle.OUTLINE,
                modifier = Modifier.weight(1f)
            )
            EtgButton(
                text = stringResource(R.string.action_continue),
                enabled = ssid.isNotBlank() && (secType == WifiSecurityType.OPEN || password.isNotBlank()),
                onClick = {
                    onAdd(
                        WifiNetworkEntry(
                            ssid = ssid.trim(),
                            password = if (secType == WifiSecurityType.OPEN) "" else password,
                            securityType = secType,
                            hidden = hidden,
                            source = "manual"
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun WifiQrPreviewDialog(
    entry: WifiNetworkEntry,
    onDismiss: () -> Unit
) {
    val g = LocalGrapheneColors.current
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.copied_to_clipboard)
    val qrPayload = remember(entry) { WifiQrAndXmlParser.toWifiQrString(entry) }

    EtgDialog(onDismissRequest = onDismiss) {
        EtgCardHeader(
            iconRes = R.drawable.ic_wifi,
            tint = g.accentCyan,
            title = entry.ssid,
            subtitle = "${entry.securityType} · ${if (entry.hidden) "Hidden" else "Visible"}",
            subtitleMono = true
        )

        QrCodeImage(
            content = qrPayload,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(224.dp),
            sizePx = 320
        )

        if (entry.password.isNotBlank()) {
            val shape = RoundedCornerShape(EtgDimens.controlRadius)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(g.surfaceContainer)
                    .border(1.dp, g.border, shape)
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Wi-Fi Password", entry.password))
                        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_key),
                    contentDescription = null,
                    tint = g.textTertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = entry.password,
                    style = MonoTechnicalStyle,
                    color = g.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.action_copy),
                    tint = g.textTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        EtgButton(
            text = stringResource(R.string.action_close),
            onClick = onDismiss,
            tone = EtgTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
