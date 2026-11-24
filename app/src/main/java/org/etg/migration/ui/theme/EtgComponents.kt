package org.etg.migration.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.etg.migration.R

enum class EtgTone { ACCENT, SUCCESS, WARNING, DANGER, NEUTRAL }

@Composable
fun EtgTone.color(): Color {
    val g = LocalGrapheneColors.current
    return when (this) {
        EtgTone.ACCENT -> g.accentCyan
        EtgTone.SUCCESS -> g.verifiedEmerald
        EtgTone.WARNING -> g.warningAmber
        EtgTone.DANGER -> g.errorRed
        EtgTone.NEUTRAL -> g.textPrimary
    }
}

@Composable
private fun EtgTone.strongColor(): Color {
    val g = LocalGrapheneColors.current
    return when (this) {
        EtgTone.ACCENT -> g.accentStrong
        EtgTone.SUCCESS -> g.emeraldStrong
        EtgTone.WARNING -> g.warningAmber
        EtgTone.DANGER -> g.errorRed
        EtgTone.NEUTRAL -> g.surfaceHigh
    }
}

@Composable
private fun EtgTone.onColor(): Color {
    val g = LocalGrapheneColors.current
    return when (this) {
        EtgTone.ACCENT -> g.onAccent
        EtgTone.SUCCESS -> g.onEmerald
        EtgTone.WARNING -> g.onWarning
        EtgTone.DANGER -> g.onError
        EtgTone.NEUTRAL -> g.textPrimary
    }
}

enum class EtgButtonStyle { FILLED, TONAL, OUTLINE, GHOST }
enum class EtgButtonSize { LARGE, MEDIUM, SMALL }

private data class ButtonSpec(
    val height: Dp,
    val radius: Dp,
    val horizontalPadding: Dp,
    val iconSize: Dp
)

@Composable
fun EtgButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    enabled: Boolean = true,
    tone: EtgTone = EtgTone.ACCENT,
    style: EtgButtonStyle = EtgButtonStyle.FILLED,
    size: EtgButtonSize = EtgButtonSize.MEDIUM
) {
    val g = LocalGrapheneColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.975f else 1f,
        animationSpec = tween(120),
        label = "btnScale"
    )

    val spec = when (size) {
        EtgButtonSize.LARGE -> ButtonSpec(EtgDimens.buttonHeightLarge, 16.dp, 20.dp, 19.dp)
        EtgButtonSize.MEDIUM -> ButtonSpec(EtgDimens.buttonHeightMedium, 13.dp, 16.dp, 17.dp)
        EtgButtonSize.SMALL -> ButtonSpec(EtgDimens.buttonHeightSmall, 10.dp, 12.dp, 15.dp)
    }
    val textStyle = if (size == EtgButtonSize.SMALL) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.labelLarge
    }
    val height = spec.height
    val hPad = spec.horizontalPadding
    val iconSize = spec.iconSize

    val shape = CircleShape
    val base = tone.color()
    val strong = tone.strongColor()

    val (background, border, content) = when {
        !enabled -> Triple<Brush?, BorderStroke?, Color>(
            SolidColor(if (g.isDark) Color(0x1FE3E2E6) else Color(0x1F1A1B1E)),
            null,
            if (g.isDark) Color(0x61E3E2E6) else Color(0x611A1B1E)
        )
        style == EtgButtonStyle.FILLED && tone == EtgTone.NEUTRAL -> Triple(
            SolidColor(g.surfaceHigh),
            null,
            g.textPrimary
        )
        style == EtgButtonStyle.FILLED -> Triple(
            SolidColor(strong),
            null,
            tone.onColor()
        )
        style == EtgButtonStyle.TONAL && tone == EtgTone.ACCENT -> Triple(
            SolidColor(g.accentContainer),
            null,
            if (g.isDark) Color(0xFFDAE2F9) else Color(0xFF121C2B)
        )
        style == EtgButtonStyle.TONAL -> Triple(
            SolidColor(base.copy(alpha = if (g.isDark) 0.16f else 0.12f)),
            null,
            if (tone == EtgTone.NEUTRAL) g.textPrimary else base
        )
        style == EtgButtonStyle.OUTLINE -> Triple(
            null,
            BorderStroke(1.dp, if (tone == EtgTone.NEUTRAL) g.borderStrong else base.copy(alpha = 0.7f)),
            if (tone == EtgTone.NEUTRAL) g.textPrimary else base
        )
        else -> Triple(null, null, if (tone == EtgTone.NEUTRAL) g.textSecondary else base)
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = Color.Transparent,
        contentColor = content,
        border = border,
        interactionSource = interaction,
        modifier = modifier
            .scale(scale)
            .heightIn(min = height)
    ) {
        Row(
            modifier = Modifier
                .then(if (background != null) Modifier.background(background) else Modifier)
                .heightIn(min = height)
                .padding(horizontal = hPad, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(iconSize)
                )
                Spacer(Modifier.width(if (size == EtgButtonSize.SMALL) 6.dp else 9.dp))
            }
            Text(
                text = text,
                style = textStyle,
                color = content,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EtgIconButton(
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalGrapheneColors.current.textSecondary,
    size: Dp = 36.dp
) {
    val g = LocalGrapheneColors.current
    val shape = RoundedCornerShape(size * 0.3f)
    Surface(
        onClick = onClick,
        shape = shape,
        color = g.surfaceContainer,
        border = BorderStroke(1.dp, g.border),
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size * 0.47f)
            )
        }
    }
}

@Composable
fun EtgScreenHeader(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val g = LocalGrapheneColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.titleSmall,
                color = g.accentCyan
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = g.textPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.textSecondary
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun EtgCardHeader(
    @DrawableRes iconRes: Int,
    tint: Color,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    subtitleColor: Color = LocalGrapheneColors.current.textSecondary,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val g = LocalGrapheneColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconBadge(iconRes = iconRes, tint = tint)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = g.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = if (subtitleMono) MonoTechnicalStyle else MaterialTheme.typography.bodySmall,
                    color = subtitleColor
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun EtgCard(
    modifier: Modifier = Modifier,
    containerColor: Color = LocalGrapheneColors.current.surface,
    borderColor: Color = LocalGrapheneColors.current.border,
    contentPadding: PaddingValues = PaddingValues(EtgDimens.cardPadding),
    spacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    FlatSectionContainer(
        modifier = modifier.fillMaxWidth(),
        containerColor = containerColor,
        borderColor = borderColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}

@Composable
fun EtgInsetPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = LocalGrapheneColors.current.border,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val g = LocalGrapheneColors.current
    val shape = RoundedCornerShape(EtgDimens.innerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(g.surfaceContainer)
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

@Composable
fun EtgBanner(
    tone: EtgTone,
    @DrawableRes iconRes: Int,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null
) {
    val g = LocalGrapheneColors.current
    val c = tone.color()
    EtgCard(
        modifier = modifier,
        containerColor = if (tone == EtgTone.ACCENT) g.accentContainer
        else c.copy(alpha = if (g.isDark) 0.08f else 0.06f).compositeOver(g.surface),
        borderColor = if (g.isDark) Color(0xFF8E9099).copy(alpha = 0.5f) else Color(0xFF74777F).copy(alpha = 0.5f),
        spacing = 12.dp
    ) {
        Row(
            verticalAlignment = if (title == null) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconBadge(iconRes = iconRes, tint = c, containerSize = 36.dp, iconSize = 18.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = g.textPrimary)
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (title == null) g.textPrimary else g.textSecondary
                )
            }
        }
        if (actions != null) actions()
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = this.alpha
    return Color(
        red = this.red * a + background.red * (1 - a),
        green = this.green * a + background.green * (1 - a),
        blue = this.blue * a + background.blue * (1 - a),
        alpha = 1f
    )
}

@Composable
fun EtgDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent),
        thickness = 1.dp,
        color = LocalGrapheneColors.current.border
    )
}

@Composable
fun EtgMetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalGrapheneColors.current.textPrimary
) {
    val g = LocalGrapheneColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
        Spacer(Modifier.width(12.dp))
        Text(value, style = NumericStyle.copy(fontSize = 13.sp), color = valueColor, maxLines = 1)
    }
}

@Composable
fun EtgStatTile(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalGrapheneColors.current.textPrimary
) {
    val g = LocalGrapheneColors.current
    val shape = RoundedCornerShape(EtgDimens.innerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(g.surfaceContainer)
            .border(1.dp, g.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = valueColor, maxLines = 1)
        Text(caption.uppercase(), style = MaterialTheme.typography.labelSmall, color = g.textTertiary, maxLines = 1)
    }
}

@Composable
fun EtgSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val g = LocalGrapheneColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = g.textPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = etgSwitchColors())
    }
}

@Composable
fun EtgCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    tone: EtgTone = EtgTone.ACCENT
) {
    val g = LocalGrapheneColors.current
    val c = tone.color()
    val fill by animateColorAsState(if (checked) c else Color.Transparent, tween(160), label = "cbFill")
    val stroke by animateColorAsState(if (checked) c else g.borderStrong, tween(160), label = "cbStroke")
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(200, easing = FastOutSlowInEasing), label = "cbCheck")
    val checkColor = tone.onColor()

    val touch = if (onCheckedChange != null) {
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
    } else {
        Modifier.size(40.dp)
    }

    Box(modifier = modifier.then(touch), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(21.dp)) {
            val r = CornerRadius(4.dp.toPx())
            drawRoundRect(color = fill, cornerRadius = r)
            drawRoundRect(
                color = stroke,
                cornerRadius = r,
                style = Stroke(width = 1.6.dp.toPx()),
                topLeft = Offset(0.8.dp.toPx(), 0.8.dp.toPx()),
                size = Size(size.width - 1.6.dp.toPx(), size.height - 1.6.dp.toPx())
            )
            if (progress > 0f) {
                val p1 = Offset(size.width * 0.27f, size.height * 0.52f)
                val p2 = Offset(size.width * 0.44f, size.height * 0.68f)
                val p3 = Offset(size.width * 0.74f, size.height * 0.35f)
                val path = Path().apply {
                    moveTo(p1.x, p1.y)
                    val firstLen = 0.4f
                    if (progress <= firstLen) {
                        val t = progress / firstLen
                        lineTo(p1.x + (p2.x - p1.x) * t, p1.y + (p2.y - p1.y) * t)
                    } else {
                        lineTo(p2.x, p2.y)
                        val t = (progress - firstLen) / (1f - firstLen)
                        lineTo(p2.x + (p3.x - p2.x) * t, p2.y + (p3.y - p2.y) * t)
                    }
                }
                drawPath(
                    path = path,
                    color = checkColor,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

@Composable
fun EtgTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    monospace: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(EtgDimens.controlRadius),
        colors = etgTextFieldColors(),
        textStyle = if (monospace) MonoMetricStyle.copy(color = LocalGrapheneColors.current.textPrimary) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
    )
}

@Composable
fun EtgPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false
) {
    val g = LocalGrapheneColors.current
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        shape = RoundedCornerShape(EtgDimens.controlRadius),
        colors = etgTextFieldColors(),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_key),
                contentDescription = null,
                tint = g.textTertiary,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painter = painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = null,
                    tint = g.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun EtgProgressBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    tone: EtgTone = EtgTone.ACCENT,
    height: Dp = 6.dp
) {
    val g = LocalGrapheneColors.current
    val c = tone.color()
    val strong = tone.strongColor()
    val animated by animateFloatAsState(
        targetValue = (fraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "progress"
    )
    val shimmer = rememberInfiniteTransition(label = "indeterminate")
    val sweep by shimmer.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(color = g.surfaceHigh, cornerRadius = r)
        if (fraction != null) {
            val w = size.width * animated
            if (w > 0f) {
                drawRoundRect(
                    color = strong,
                    size = Size(w.coerceAtLeast(size.height), size.height),
                    cornerRadius = r
                )
            }
        } else {
            val segW = size.width * 0.4f
            val x = size.width * sweep
            val left = x.coerceAtLeast(0f)
            val right = (x + segW).coerceAtMost(size.width)
            if (right > left) {
                drawRoundRect(
                    color = strong,
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                    cornerRadius = r
                )
            }
        }
    }
}

@Composable
fun EtgDialog(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val g = LocalGrapheneColors.current
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = g.surface,
            border = BorderStroke(1.dp, if (g.isDark) g.borderStrong else g.border),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}
