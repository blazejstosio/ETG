package org.etg.migration.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import org.etg.migration.R
import org.etg.migration.data.integration.SinusChatBridge
import org.etg.migration.model.PickedFile
import org.etg.migration.ui.SinusPhase
import org.etg.migration.ui.theme.EtgButton
import org.etg.migration.ui.theme.EtgButtonSize
import org.etg.migration.ui.theme.EtgButtonStyle
import org.etg.migration.ui.theme.EtgIconButton
import org.etg.migration.ui.theme.EtgInsetPanel
import org.etg.migration.ui.theme.EtgProgressBar
import org.etg.migration.ui.theme.FormatUtils
import org.etg.migration.ui.theme.GrapheneLogoMark
import org.etg.migration.ui.theme.LocalGrapheneColors
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun SinusHandoffPanel(
    phase: SinusPhase,
    progress: Float?,
    received: PickedFile?,
    receivedAt: Long,
    withMedia: Boolean,
    onStart: () -> Unit,
    onRemove: (PickedFile) -> Unit
) {
    val g = LocalGrapheneColors.current
    val ready = phase == SinusPhase.IDLE && received != null
    EtgInsetPanel(spacing = 14.dp) {
        HandoffLine(active = phase != SinusPhase.IDLE, ready = ready)
        AnimatedContent(
            targetState = if (ready) "ready" else phase.name,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "sinusPhase"
        ) { key ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                when (key) {
                    SinusPhase.WAITING.name -> {
                        PhaseText(stringResource(R.string.sinus_wait_title), stringResource(R.string.sinus_wait_desc))
                    }
                    SinusPhase.RECEIVING.name -> {
                        PhaseText(stringResource(R.string.sinus_receiving_title), stringResource(R.string.sinus_receiving_desc))
                        EtgProgressBar(fraction = progress)
                    }
                    "ready" -> {
                        val file = received ?: return@Column
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.sinus_ready_title), style = MaterialTheme.typography.titleSmall, color = g.verifiedEmerald)
                                val details = listOfNotNull(
                                    FormatUtils.formatBytes(file.sizeBytes),
                                    receivedAt.takeIf { it > 0 }?.let {
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                                    },
                                    stringResource(if (withMedia) R.string.sinus_ready_media else R.string.sinus_ready_no_media)
                                ).joinToString(" · ")
                                Text(details, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
                            }
                            EtgIconButton(
                                iconRes = R.drawable.ic_delete,
                                onClick = { onRemove(file) },
                                tint = g.errorRed,
                                size = 30.dp
                            )
                        }
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lock),
                                contentDescription = null,
                                tint = g.textTertiary,
                                modifier = Modifier.padding(top = 2.dp).size(14.dp)
                            )
                            Text(stringResource(R.string.sinus_ready_note), style = MaterialTheme.typography.bodySmall, color = g.textTertiary)
                        }
                        EtgButton(
                            text = stringResource(R.string.sinus_again_button),
                            onClick = onStart,
                            iconRes = R.drawable.ic_refresh,
                            style = EtgButtonStyle.TONAL,
                            size = EtgButtonSize.SMALL
                        )
                    }
                    else -> {
                        EtgButton(
                            text = stringResource(R.string.sinus_export_button),
                            onClick = onStart,
                            iconRes = R.drawable.ic_chat,
                            style = EtgButtonStyle.TONAL,
                            size = EtgButtonSize.SMALL
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseText(title: String, desc: String) {
    val g = LocalGrapheneColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = g.textPrimary)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
    }
}

@Composable
private fun HandoffLine(active: Boolean, ready: Boolean) {
    val g = LocalGrapheneColors.current
    val context = LocalContext.current
    val sinusIcon: ImageBitmap? = remember {
        runCatching {
            context.packageManager.getApplicationIcon(SinusChatBridge.PACKAGE).toBitmap(144, 144).asImageBitmap()
        }.getOrNull()
    }
    val transition = rememberInfiniteTransition(label = "handoff")
    val flow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "flow"
    )
    val lineColor by animateColorAsState(if (ready) g.verifiedEmerald else g.accentCyan, tween(300), label = "lineColor")
    val activity by animateFloatAsState(if (active) 1f else 0f, tween(300), label = "activity")
    val done by animateFloatAsState(if (ready) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 300f), label = "done")
    val pulse = if (active) 1f + 0.08f * sin(2 * PI * flow).toFloat() else 1f
    val track = g.textTertiary

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppBubble {
            if (sinusIcon != null) {
                Image(bitmap = sinusIcon, contentDescription = null, modifier = Modifier.size(48.dp).clip(CircleShape))
            } else {
                Icon(painterResource(R.drawable.ic_chat), null, tint = g.accentCyan, modifier = Modifier.size(24.dp))
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp)) {
                val cy = size.height / 2f
                val gap = 24.dp.toPx()
                val n = 12
                val r = 2.6.dp.toPx()
                for (i in 0 until n) {
                    val t = i / (n - 1f)
                    val x = t * size.width
                    if (kotlin.math.abs(x - size.width / 2f) < gap) continue
                    val behind = ((flow - t) % 1f + 1f) % 1f
                    val glow = (1f - behind / 0.4f).coerceAtLeast(0f)
                    val alpha = when {
                        done > 0f -> 0.25f + 0.75f * done.coerceAtMost(1f)
                        else -> 0.22f + 0.78f * glow * activity
                    }
                    val color = if (done > 0f || glow * activity > 0.05f) lineColor else track
                    drawCircle(color.copy(alpha = alpha.coerceIn(0f, 1f)), radius = r * (1f + 0.35f * glow * activity), center = Offset(x, cy))
                }
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(lineColor.copy(alpha = if (g.isDark) 0.18f else 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                if (done > 0.01f) {
                    Canvas(modifier = Modifier.size(18.dp).scale(done.coerceIn(0f, 1.2f))) {
                        val p = Path().apply {
                            moveTo(size.width * 0.18f, size.height * 0.54f)
                            lineTo(size.width * 0.42f, size.height * 0.76f)
                            lineTo(size.width * 0.84f, size.height * 0.28f)
                        }
                        drawPath(p, lineColor, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                } else {
                    Icon(painterResource(R.drawable.ic_lock), null, tint = lineColor, modifier = Modifier.size(16.dp))
                }
            }
        }
        AppBubble {
            GrapheneLogoMark(iconSize = 26.dp, color = g.accentCyan)
        }
    }
}

@Composable
private fun AppBubble(content: @Composable () -> Unit) {
    val g = LocalGrapheneColors.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(g.surfaceHigh),
        contentAlignment = Alignment.Center
    ) { content() }
}
