package org.etg.migration.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.etg.migration.R
import org.etg.migration.data.transfer.InterfaceKind
import org.etg.migration.data.transfer.NetworkInterfaceInspector
import org.etg.migration.data.transfer.NfcPairing
import org.etg.migration.data.transfer.PeerRequest
import org.etg.migration.data.transfer.TransferProtocol
import org.etg.migration.ui.EtgUiState
import org.etg.migration.ui.EtgViewModel
import org.etg.migration.ui.ReceiverPhase
import org.etg.migration.ui.TransferLinkMode
import org.etg.migration.ui.TransferRole
import org.etg.migration.ui.components.QrScannerDialog
import org.etg.migration.ui.theme.EtgButton
import org.etg.migration.ui.theme.EtgButtonSize
import org.etg.migration.ui.theme.EtgButtonStyle
import org.etg.migration.ui.theme.EtgCard
import org.etg.migration.ui.theme.EtgCardHeader
import org.etg.migration.ui.theme.EtgDialog
import org.etg.migration.ui.theme.EtgDimens
import org.etg.migration.ui.theme.EtgDivider
import org.etg.migration.ui.theme.EtgIconButton
import org.etg.migration.ui.theme.EtgProgressBar
import org.etg.migration.ui.theme.EtgScreenHeader
import org.etg.migration.ui.theme.EtgSwitchRow
import org.etg.migration.ui.theme.EtgTextField
import org.etg.migration.ui.theme.EtgTone
import org.etg.migration.ui.theme.FlatSectionContainer
import org.etg.migration.ui.theme.FormatUtils
import org.etg.migration.ui.theme.IconBadge
import org.etg.migration.ui.theme.LocalGrapheneColors
import org.etg.migration.ui.theme.MonoTechnicalStyle
import org.etg.migration.ui.theme.QrCodeImage
import org.etg.migration.ui.theme.SectionHeader
import org.etg.migration.ui.theme.SegmentedPillSelector
import org.etg.migration.ui.theme.StatusPill

@Composable
fun TransferScreen(
    state: EtgUiState,
    viewModel: EtgViewModel
) {
    val context = LocalContext.current
    val g = LocalGrapheneColors.current
    val copiedMsg = stringResource(R.string.copied_to_clipboard)
    var showPairingScanner by remember { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var nfcState by remember { mutableStateOf(NfcPairing.availability(context)) }

    LifecycleResumeEffect(Unit) {
        nfcState = NfcPairing.availability(context)
        onPauseOrDispose { }
    }

    val listenNfc = state.transferRole == TransferRole.RECEIVE &&
        state.receiverPhase == ReceiverPhase.IDLE &&
        nfcState == NfcPairing.Availability.AVAILABLE
    val currentVm by rememberUpdatedState(viewModel)
    DisposableEffect(listenNfc) {
        val activity = context.findActivity()
        val stop = if (listenNfc && activity != null) {
            NfcPairing.startReader(activity) { uri ->
                activity.runOnUiThread { currentVm.connectWithPairingUri(uri) }
            }
        } else {
            {}
        }
        onDispose { stop() }
    }

    fun copyText(label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
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
                eyebrow = stringResource(R.string.screen_transfer_eyebrow),
                title = stringResource(R.string.screen_transfer_title),
                subtitle = stringResource(R.string.screen_transfer_subtitle)
            )
        }

        item(key = "security_note") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = g.verifiedEmerald,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.tx_security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
            }
        }

        item(key = "role_selector") {
            SegmentedPillSelector(
                options = listOf(
                    TransferRole.SEND to stringResource(R.string.tx_send),
                    TransferRole.RECEIVE to stringResource(R.string.tx_receive)
                ),
                selected = state.transferRole,
                onSelect = { viewModel.setTransferRole(it) },
                iconFor = { role -> if (role == TransferRole.SEND) R.drawable.ic_upload else R.drawable.ic_download }
            )
        }

        if (state.transferRole == TransferRole.SEND) {
            item(key = "sender") {
                if (!state.senderRunning) {
                    SenderIntroCard(state, onStart = { viewModel.startTransferSender() })
                } else {
                    SenderPairingCard(
                        state = state,
                        nfcState = nfcState,
                        onStop = { viewModel.stopTransferSender() }
                    )
                }
            }
        } else {
            when (state.receiverPhase) {
                ReceiverPhase.IDLE -> {
                    item(key = "rx_qr") {
                        ReceiveOptionCard(
                            step = 1,
                            iconRes = R.drawable.ic_qr_scan,
                            tint = g.accentCyan,
                            title = stringResource(R.string.tx_receive_scan),
                            desc = stringResource(R.string.tx_receive_scan_desc)
                        ) {
                            EtgButton(
                                text = stringResource(R.string.tx_receive_scan),
                                onClick = { showPairingScanner = true },
                                iconRes = R.drawable.ic_qr_scan,
                                size = EtgButtonSize.LARGE,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item(key = "rx_nfc") {
                        NfcListenCard(nfcState = nfcState)
                    }
                    item(key = "rx_code") {
                        ReceiveOptionCard(
                            step = 3,
                            iconRes = R.drawable.ic_key,
                            tint = g.textSecondary,
                            title = stringResource(R.string.tx_receive_code),
                            desc = null
                        ) {
                            EtgTextField(
                                value = state.receiveCode,
                                onValueChange = { viewModel.setReceiveCode(it) },
                                label = stringResource(R.string.tx_receive_code_hint),
                                keyboardType = KeyboardType.Ascii,
                                monospace = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            EtgButton(
                                text = stringResource(R.string.tx_connect),
                                onClick = { viewModel.connectWithCode() },
                                enabled = TransferProtocol.isValidShortCode(state.receiveCode),
                                style = EtgButtonStyle.TONAL,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                ReceiverPhase.CONNECTING, ReceiverPhase.RECEIVING -> item(key = "rx_connecting") {
                    EtgCard(contentPadding = PaddingValues(20.dp)) {
                        Text(
                            text = stringResource(R.string.tx_connecting),
                            style = MaterialTheme.typography.titleMedium,
                            color = g.textPrimary
                        )
                        EtgProgressBar(fraction = null)
                        EtgButton(
                            text = stringResource(R.string.tx_cancel),
                            onClick = { viewModel.cancelReceive() },
                            tone = EtgTone.NEUTRAL,
                            style = EtgButtonStyle.OUTLINE,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                ReceiverPhase.AWAITING_APPROVAL -> item(key = "rx_awaiting") {
                    EtgCard(contentPadding = PaddingValues(20.dp), spacing = 16.dp) {
                        EtgCardHeader(
                            iconRes = R.drawable.ic_shield,
                            tint = g.accentCyan,
                            title = stringResource(R.string.tx_awaiting_title),
                            subtitle = state.receiverPeerName.ifBlank { null }
                        )
                        VerificationCodeDisplay(state.receiverVerificationCode)
                        Text(
                            text = stringResource(R.string.tx_awaiting_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = g.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        EtgProgressBar(fraction = null)
                        EtgButton(
                            text = stringResource(R.string.tx_cancel),
                            onClick = { viewModel.cancelReceive() },
                            tone = EtgTone.NEUTRAL,
                            style = EtgButtonStyle.OUTLINE,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item(key = "advanced_toggle") {
            val rotation by animateFloatAsState(if (advancedOpen) 180f else 0f, tween(200), label = "advRot")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { advancedOpen = !advancedOpen }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.tx_advanced),
                    style = MaterialTheme.typography.titleSmall,
                    color = g.accentCyan,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = g.accentCyan,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation)
                )
            }
        }

        if (advancedOpen) {
            item(key = "adv_link") {
                SegmentedPillSelector(
                    options = listOf(
                        TransferLinkMode.WIRELESS to stringResource(R.string.transfer_mode_wireless),
                        TransferLinkMode.WIRED to stringResource(R.string.transfer_mode_wired)
                    ),
                    selected = state.transferLinkMode,
                    onSelect = { viewModel.setTransferLinkMode(it) },
                    iconFor = { mode -> if (mode == TransferLinkMode.WIRELESS) R.drawable.ic_wireless else R.drawable.ic_cable }
                )
            }

            if (state.transferRole == TransferRole.RECEIVE) {
                item(key = "adv_manual_host") {
                    EtgCard {
                        EtgTextField(
                            value = state.receiveHost,
                            onValueChange = { viewModel.setReceiveFields(it, state.receivePort, state.receivePin) },
                            label = stringResource(R.string.tx_manual_host),
                            keyboardType = KeyboardType.Uri,
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (state.discoveredPeers.isNotEmpty()) {
                            state.discoveredPeers.forEach { peer ->
                                PeerRow(
                                    title = peer.serviceName.removePrefix("ETG-").replace('_', ' '),
                                    badge = "${peer.host}:${peer.port}",
                                    badgeColor = g.verifiedEmerald,
                                    iconRes = R.drawable.ic_wifi,
                                    onClick = { viewModel.setReceiveFields(peer.host, peer.port.toString(), state.receivePin) }
                                )
                            }
                        }
                    }
                }
            } else {
                item(key = "adv_web") {
                    EtgCard {
                        EtgSwitchRow(
                            title = stringResource(R.string.transfer_web_toggle),
                            checked = state.webServerEnabled,
                            onCheckedChange = { viewModel.toggleWebServer(it) }
                        )
                        AnimatedVisibility(visible = state.webServerRunning) {
                            val activeHost = if (state.transferLinkMode == TransferLinkMode.WIRED) {
                                NetworkInterfaceInspector.bestWiredHost(state.netInterfaces)
                            } else {
                                NetworkInterfaceInspector.bestWirelessHost(state.netInterfaces)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val localUrl = "http://$activeHost:${state.webServerPort}"
                                val adbUrl = "http://127.0.0.1:${state.webServerPort}"
                                StatusPill(text = "PIN ${state.senderPin}", color = g.accentCyan)
                                CopyableCodeLine(localUrl) { copyText("Web URL", localUrl) }
                                CopyableCodeLine(adbUrl) { copyText("ADB Web URL", adbUrl) }
                            }
                        }
                    }
                }
            }

            if (state.transferLinkMode == TransferLinkMode.WIRED) {
                item(key = "wired_card") {
                    EtgCard {
                        EtgCardHeader(
                            iconRes = R.drawable.ic_cable,
                            tint = g.warningAmber,
                            title = "USB-C Tether / ADB"
                        )
                        Text(
                            stringResource(R.string.transfer_adb_guide),
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                        val adbTcpCmd = "adb forward tcp:${state.transferPort} tcp:${state.transferPort}"
                        val adbWebCmd = "adb forward tcp:${state.webServerPort} tcp:${state.webServerPort}"
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CopyableCodeLine(adbTcpCmd, prompt = true) { copyText("ADB TCP", adbTcpCmd) }
                            CopyableCodeLine(adbWebCmd, prompt = true) { copyText("ADB HTTP", adbWebCmd) }
                        }
                        EtgButton(
                            text = stringResource(R.string.transfer_usb_tether_btn),
                            onClick = {
                                runCatching {
                                    context.startActivity(NetworkInterfaceInspector.openUsbTetheringSettingsIntent())
                                }.onFailure {
                                    runCatching { context.startActivity(NetworkInterfaceInspector.openFallbackWirelessSettingsIntent()) }
                                }
                            },
                            iconRes = R.drawable.ic_usb,
                            style = EtgButtonStyle.TONAL,
                            size = EtgButtonSize.SMALL
                        )
                    }
                }
            } else {
                item(key = "wireless_p2p_card") {
                    EtgCard {
                        EtgCardHeader(
                            iconRes = R.drawable.ic_wifi,
                            tint = g.accentCyan,
                            title = "Wi-Fi Direct (P2P)",
                            subtitle = state.wifiDirectState.statusText.ifBlank { null },
                            subtitleMono = true,
                            subtitleColor = g.accentCyan
                        )
                        if (state.transferRole == TransferRole.SEND) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                EtgButton(
                                    text = stringResource(R.string.transfer_wifi_p2p_create),
                                    onClick = { viewModel.createWifiDirectGroup() },
                                    style = EtgButtonStyle.TONAL,
                                    size = EtgButtonSize.SMALL
                                )
                                if (state.wifiDirectState.groupActive) {
                                    EtgButton(
                                        text = stringResource(R.string.transfer_stop_sender),
                                        onClick = { viewModel.stopWifiDirectGroup() },
                                        tone = EtgTone.DANGER,
                                        style = EtgButtonStyle.OUTLINE,
                                        size = EtgButtonSize.SMALL
                                    )
                                }
                            }
                        } else {
                            EtgButton(
                                text = stringResource(R.string.transfer_wifi_p2p_discover),
                                onClick = { viewModel.discoverWifiDirectPeers() },
                                iconRes = R.drawable.ic_refresh,
                                style = EtgButtonStyle.TONAL,
                                size = EtgButtonSize.SMALL
                            )
                            state.wifiDirectState.peers.forEach { dev ->
                                PeerRow(
                                    title = dev.deviceName ?: dev.deviceAddress,
                                    badge = "P2P",
                                    badgeColor = g.accentCyan,
                                    iconRes = R.drawable.ic_wireless,
                                    onClick = { viewModel.connectWifiDirectPeer(dev.deviceAddress) }
                                )
                            }
                        }
                    }
                }
            }

            item(key = "net_interfaces_header") {
                SectionHeader(
                    title = stringResource(R.string.transfer_network_section),
                    modifier = Modifier.padding(top = 6.dp),
                    trailing = {
                        EtgIconButton(
                            iconRes = R.drawable.ic_refresh,
                            onClick = { viewModel.refreshNetworkInterfaces() },
                            size = 30.dp
                        )
                    }
                )
            }

            item(key = "net_interfaces_card") {
                FlatSectionContainer {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        state.netInterfaces.forEachIndexed { idx, iface ->
                            if (idx > 0) EtgDivider(startIndent = 16.dp)
                            val (badgeText, badgeColor) = when (iface.kind) {
                                InterfaceKind.USB_TETHER -> "USB" to g.warningAmber
                                InterfaceKind.WIFI_DIRECT -> "P2P" to g.verifiedEmerald
                                InterfaceKind.WIFI_LAN -> "LAN" to g.accentCyan
                                InterfaceKind.LOOPBACK_ADB -> "ADB" to g.textSecondary
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    StatusPill(text = badgeText, color = badgeColor)
                                    Text(iface.name, style = MonoTechnicalStyle, color = g.textTertiary)
                                }
                                Text(iface.ipAddress, style = MonoTechnicalStyle, color = g.textPrimary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPairingScanner) {
        val invalidMsg = stringResource(R.string.tx_invalid_qr)
        QrScannerDialog(
            title = stringResource(R.string.tx_receive_scan),
            subtitle = stringResource(R.string.tx_receive_scan_desc),
            onResult = { raw ->
                showPairingScanner = false
                if (!viewModel.connectWithPairingUri(raw)) {
                    Toast.makeText(context, invalidMsg, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showPairingScanner = false }
        )
    }

    state.pendingPeerRequest?.let { request ->
        PeerApprovalDialog(
            request = request,
            onAllow = { viewModel.respondToPeerRequest(true) },
            onDeny = { viewModel.respondToPeerRequest(false) }
        )
    }
}

@Composable
private fun SenderIntroCard(state: EtgUiState, onStart: () -> Unit) {
    val g = LocalGrapheneColors.current
    val selected = state.exportConfig.selectedCategories
    val bytes = selected.sumOf { state.scanInfos[it]?.estimatedBytes ?: 0L }
    EtgCard(contentPadding = PaddingValues(20.dp), spacing = 16.dp) {
        EtgCardHeader(
            iconRes = R.drawable.ic_upload,
            tint = g.accentCyan,
            title = stringResource(R.string.tx_send_intro_title),
            subtitle = stringResource(
                R.string.export_selected_summary,
                selected.size,
                org.etg.migration.model.ExportCategory.entries.size,
                FormatUtils.formatBytes(bytes)
            )
        )
        Text(
            text = stringResource(R.string.tx_send_intro_desc),
            style = MaterialTheme.typography.bodySmall,
            color = g.textSecondary
        )
        if (state.senderStatusMessage.isNotBlank()) {
            StatusPill(text = state.senderStatusMessage, color = g.verifiedEmerald, showDot = true)
        }
        EtgButton(
            text = stringResource(R.string.tx_start),
            onClick = onStart,
            enabled = selected.isNotEmpty(),
            iconRes = R.drawable.ic_lock,
            size = EtgButtonSize.LARGE,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SenderPairingCard(
    state: EtgUiState,
    nfcState: NfcPairing.Availability,
    onStop: () -> Unit
) {
    val g = LocalGrapheneColors.current
    val context = LocalContext.current
    EtgCard(
        borderColor = g.accentCyan.copy(alpha = 0.35f),
        contentPadding = PaddingValues(20.dp),
        spacing = 18.dp
    ) {
        Text(
            text = stringResource(R.string.tx_scan_this),
            style = MaterialTheme.typography.titleMedium,
            color = g.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.senderPairingUri.isNotBlank()) {
            QrCodeImage(
                content = state.senderPairingUri,
                sizePx = 360,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(236.dp)
            )
        }

        if (nfcState != NfcPairing.Availability.UNSUPPORTED) {
            AlternativeRow(
                iconRes = R.drawable.ic_wireless,
                text = stringResource(R.string.tx_or_nfc),
                trailing = if (nfcState == NfcPairing.Availability.DISABLED) {
                    {
                        EtgButton(
                            text = stringResource(R.string.tx_nfc_off),
                            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } },
                            tone = EtgTone.WARNING,
                            style = EtgButtonStyle.TONAL,
                            size = EtgButtonSize.SMALL
                        )
                    }
                } else null
            )
        }

        AlternativeRow(iconRes = R.drawable.ic_key, text = stringResource(R.string.tx_or_code))
        Text(
            text = TransferProtocol.formatShortCode(state.senderShortCode),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            letterSpacing = 3.sp,
            color = g.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        EtgDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PulsingDot(g.accentCyan)
            Text(
                text = stringResource(R.string.tx_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = g.textSecondary
            )
        }
        EtgButton(
            text = stringResource(R.string.tx_stop),
            onClick = onStop,
            tone = EtgTone.DANGER,
            style = EtgButtonStyle.TONAL,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AlternativeRow(
    iconRes: Int,
    text: String,
    trailing: (@Composable () -> Unit)? = null
) {
    val g = LocalGrapheneColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(g.border))
        Spacer(Modifier.width(10.dp))
        Icon(painterResource(iconRes), contentDescription = null, tint = g.textTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = g.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(g.border))
    }
    if (trailing != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { trailing() }
    }
}

@Composable
private fun PeerApprovalDialog(
    request: PeerRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val g = LocalGrapheneColors.current
    EtgDialog(onDismissRequest = {}) {
        IconBadge(
            iconRes = R.drawable.ic_shield,
            tint = g.warningAmber,
            containerSize = 52.dp,
            iconSize = 26.dp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            text = stringResource(R.string.tx_approve_title),
            style = MaterialTheme.typography.titleLarge,
            color = g.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.tx_approve_desc, request.deviceName),
            style = MaterialTheme.typography.bodyMedium,
            color = g.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        VerificationCodeDisplay(request.verificationCode)
        if (request.pairedViaCode) {
            Text(
                text = stringResource(R.string.tx_approve_code_warning),
                style = MaterialTheme.typography.bodySmall,
                color = g.warningAmber,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EtgButton(
                text = stringResource(R.string.tx_deny),
                onClick = onDeny,
                tone = EtgTone.DANGER,
                style = EtgButtonStyle.OUTLINE,
                modifier = Modifier.weight(1f)
            )
            EtgButton(
                text = stringResource(R.string.tx_allow),
                onClick = onAllow,
                iconRes = R.drawable.ic_lock,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ReceiveOptionCard(
    step: Int,
    iconRes: Int,
    tint: Color,
    title: String,
    desc: String?,
    content: @Composable () -> Unit
) {
    val g = LocalGrapheneColors.current
    EtgCard(contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(iconRes = iconRes, tint = tint)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = g.textPrimary)
                if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
            }
            Text("$step", style = MonoTechnicalStyle, color = g.textTertiary)
        }
        content()
    }
}

@Composable
private fun NfcListenCard(nfcState: NfcPairing.Availability) {
    val g = LocalGrapheneColors.current
    val context = LocalContext.current
    val (title, desc, tint) = when (nfcState) {
        NfcPairing.Availability.AVAILABLE -> Triple(
            stringResource(R.string.tx_receive_nfc),
            stringResource(R.string.tx_receive_nfc_desc),
            g.verifiedEmerald
        )
        NfcPairing.Availability.DISABLED -> Triple(
            stringResource(R.string.tx_receive_nfc),
            stringResource(R.string.tx_receive_nfc_off),
            g.warningAmber
        )
        NfcPairing.Availability.UNSUPPORTED -> Triple(
            stringResource(R.string.tx_receive_nfc),
            stringResource(R.string.tx_receive_nfc_none),
            g.textTertiary
        )
    }
    ReceiveOptionCard(step = 2, iconRes = R.drawable.ic_wireless, tint = tint, title = title, desc = desc) {
        when (nfcState) {
            NfcPairing.Availability.AVAILABLE -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PulsingDot(g.verifiedEmerald)
                Text("NFC", style = MonoTechnicalStyle, color = g.verifiedEmerald)
            }
            NfcPairing.Availability.DISABLED -> EtgButton(
                text = stringResource(R.string.tx_nfc_off),
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } },
                tone = EtgTone.WARNING,
                style = EtgButtonStyle.TONAL,
                size = EtgButtonSize.SMALL
            )
            NfcPairing.Availability.UNSUPPORTED -> Unit
        }
    }
}

@Composable
private fun VerificationCodeDisplay(code: String) {
    val g = LocalGrapheneColors.current
    val shape = RoundedCornerShape(EtgDimens.innerRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(g.accentContainer)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            letterSpacing = 2.sp,
            color = if (g.isDark) Color(0xFFDAE2F9) else Color(0xFF121C2B)
        )
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(0.6f, 1.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseScale")
    Box(
        modifier = Modifier
            .size(10.dp)
            .scale(s)
            .background(color, CircleShape)
    )
}

@Composable
private fun PeerRow(
    title: String,
    badge: String,
    badgeColor: Color,
    iconRes: Int,
    onClick: () -> Unit
) {
    val g = LocalGrapheneColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(g.surfaceContainer)
            .border(1.dp, badgeColor.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = badgeColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = g.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        StatusPill(text = badge, color = badgeColor, showDot = true)
    }
}

@Composable
private fun CopyableCodeLine(
    code: String,
    accentColor: Color = LocalGrapheneColors.current.textPrimary,
    prompt: Boolean = false,
    onCopy: () -> Unit
) {
    val g = LocalGrapheneColors.current
    val shape = RoundedCornerShape(EtgDimens.controlRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(g.surfaceContainer)
            .border(1.dp, g.border, shape)
            .clickable(onClick = onCopy)
            .padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prompt) Text("$ ", style = MonoTechnicalStyle, color = g.textTertiary)
        Text(
            text = code,
            style = MonoTechnicalStyle,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            painter = painterResource(R.drawable.ic_copy),
            contentDescription = stringResource(R.string.action_copy),
            tint = g.textTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
