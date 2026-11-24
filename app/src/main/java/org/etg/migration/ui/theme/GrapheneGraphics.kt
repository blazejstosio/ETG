package org.etg.migration.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.etg.migration.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun FlatSectionContainer(
    modifier: Modifier = Modifier,
    containerColor: Color = LocalGrapheneColors.current.surface,
    borderColor: Color = LocalGrapheneColors.current.border,
    cornerRadius: Dp = EtgDimens.cardRadius,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content
    )
}

@Composable
fun GrapheneHeroCard(
    modifier: Modifier = Modifier,
    accentColor: Color = LocalGrapheneColors.current.accentCyan,
    content: @Composable BoxScope.() -> Unit
) {
    val g = LocalGrapheneColors.current
    val shape = RoundedCornerShape(EtgDimens.cardRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(g.surfaceContainer)
            .border(1.dp, g.border, shape)
    ) {
        GrapheneLogoMark(
            iconSize = 150.dp,
            color = accentColor.copy(alpha = if (g.isDark) 0.10f else 0.09f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 38.dp, y = (-22).dp)
        )
        content()
    }
}

@Composable
fun GrapheneLogoMark(
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp,
    color: Color = LocalGrapheneColors.current.textPrimary
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        val r = size.minDimension / 2f / 1.63f
        val bond = r * 0.082f
        val atom = r * 0.235f
        val stub = r * 0.63f
        val vertices = (0 until 6).map { i ->
            val a = PI / 3.0 * i - PI / 2.0
            Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()) to a
        }
        vertices.forEachIndexed { i, (p, a) ->
            val next = vertices[(i + 1) % 6].first
            drawLine(color, p, next, strokeWidth = bond)
            val outer = Offset(p.x + stub * cos(a).toFloat(), p.y + stub * sin(a).toFloat())
            drawLine(color, p, outer, strokeWidth = bond)
        }
        vertices.forEach { (p, _) -> drawCircle(color, radius = atom, center = p) }
    }
}

@Composable
fun IconBadge(
    @DrawableRes iconRes: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    containerSize: Dp = 40.dp,
    iconSize: Dp = 20.dp
) {
    val g = LocalGrapheneColors.current
    val shape = RoundedCornerShape(containerSize * 0.25f)
    Box(
        modifier = modifier
            .size(containerSize)
            .clip(shape)
            .background(tint.copy(alpha = if (g.isDark) 0.14f else 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val g = LocalGrapheneColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = g.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun <T> SegmentedPillSelector(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    iconFor: ((T) -> Int?)? = null
) {
    val g = LocalGrapheneColors.current
    val trackShape = CircleShape
    val thumbShape = CircleShape
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val inset = 4.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(trackShape)
            .background(g.surfaceContainer)
            .border(1.dp, g.border, trackShape)
            .padding(inset)
    ) {
        val segmentWidth = maxWidth / options.size.coerceAtLeast(1)
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
            label = "segThumb"
        )
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(thumbShape)
                .background(g.accentContainer)
        )
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val textCol by animateColorAsState(
                    targetValue = if (isSelected) (if (g.isDark) Color(0xFFDAE2F9) else Color(0xFF121C2B)) else g.textSecondary,
                    animationSpec = tween(180),
                    label = "segText"
                )
                val iconCol by animateColorAsState(
                    targetValue = if (isSelected) (if (g.isDark) Color(0xFFDAE2F9) else Color(0xFF121C2B)) else g.textTertiary,
                    animationSpec = tween(180),
                    label = "segIcon"
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(thumbShape)
                        .clickable(role = Role.Tab) { onSelect(value) }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val iconRes = iconFor?.invoke(value)
                    if (iconRes != null) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = iconCol,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = textCol,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp
) {
    Box(
        modifier = modifier
            .size(size + 4.dp)
            .background(color.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .background(color, CircleShape)
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    showDot: Boolean = false
) {
    val shape = RoundedCornerShape(EtgDimens.chipRadius)
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.13f), shape)
            .padding(horizontal = 9.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
        }
        Text(
            text = text,
            color = color,

            style = if (',' in text) NumericStyle.copy(fontSize = 11.5.sp) else MonoTechnicalStyle,
            maxLines = 1
        )
    }
}

@Composable
fun PasswordStrengthBar(
    password: String,
    modifier: Modifier = Modifier
) {
    val g = LocalGrapheneColors.current
    val score = remember(password) {
        if (password.isEmpty()) 0
        else {
            var s = 0
            if (password.length >= 4) s++
            if (password.length >= 8 && (password.any { it.isDigit() } || password.any { it.isUpperCase() })) s++
            if (password.length >= 12 && password.any { !it.isLetterOrDigit() }) s++
            s.coerceAtLeast(1)
        }
    }
    val (labelRes, activeColor) = when (score) {
        0 -> null to g.border
        1 -> R.string.password_strength_weak to g.errorRed
        2 -> R.string.password_strength_fair to g.warningAmber
        else -> R.string.password_strength_strong to g.verifiedEmerald
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        for (segment in 1..3) {
            val segColor by animateColorAsState(
                targetValue = if (segment <= score) activeColor else g.surfaceHigh,
                animationSpec = tween(220),
                label = "pwSeg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(segColor)
            )
        }
        Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
            if (labelRes != null) {
                Text(
                    text = stringResource(labelRes),
                    style = MonoTechnicalStyle,
                    color = activeColor
                )
            }
        }
    }
}

@Composable
fun etgTextFieldColors(): TextFieldColors {
    val g = LocalGrapheneColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = g.accentCyan,
        unfocusedBorderColor = g.border,
        disabledBorderColor = g.border,
        focusedLabelColor = g.accentCyan,
        unfocusedLabelColor = g.textSecondary,
        cursorColor = g.accentCyan,
        focusedTextColor = g.textPrimary,
        unfocusedTextColor = g.textPrimary,
        focusedPlaceholderColor = g.textTertiary,
        unfocusedPlaceholderColor = g.textTertiary,
        focusedContainerColor = g.surfaceContainer,
        unfocusedContainerColor = g.surfaceContainer,
        focusedTrailingIconColor = g.textSecondary,
        unfocusedTrailingIconColor = g.textTertiary
    )
}

@Composable
fun etgSwitchColors(): SwitchColors {
    val g = LocalGrapheneColors.current
    return SwitchDefaults.colors(
        checkedThumbColor = g.onAccent,
        checkedTrackColor = g.accentStrong,
        checkedBorderColor = Color.Transparent,
        checkedIconColor = g.accentStrong,
        uncheckedThumbColor = if (g.isDark) g.textSecondary else Color.White,
        uncheckedTrackColor = g.surfaceHigh,
        uncheckedBorderColor = if (g.isDark) g.borderStrong else g.borderStrong
    )
}

@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    sizePx: Int = 256
) {
    val g = LocalGrapheneColors.current
    val bitmap = remember(content, sizePx) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) 0xFF0B1220.toInt() else AndroidColor.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        val shape = RoundedCornerShape(EtgDimens.cardRadius)
        Box(
            modifier = modifier
                .clip(shape)
                .background(Color.White)
                .border(1.dp, if (g.isDark) g.borderStrong else g.border, shape)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "QR Code",
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

object FormatUtils {
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    fun formatSpeed(bytesPerSec: Long): String =
        if (bytesPerSec <= 0L) "0 KB/s" else "${formatBytes(bytesPerSec)}/s"

    fun formatEta(seconds: Long): String {
        if (seconds < 0L) return "--:--"
        val m = seconds / 60L
        val s = seconds % 60L
        return "%02d:%02d".format(m, s)
    }
}
