package org.etg.migration.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.etg.migration.R
import org.etg.migration.data.exporter.SelectableItem
import org.etg.migration.data.exporter.SettingsCatalog
import org.etg.migration.ui.theme.EtgButton
import org.etg.migration.ui.theme.EtgButtonSize
import org.etg.migration.ui.theme.EtgButtonStyle
import org.etg.migration.ui.theme.EtgCheckbox
import org.etg.migration.ui.theme.EtgDialog
import org.etg.migration.ui.theme.EtgDimens
import org.etg.migration.ui.theme.EtgDivider
import org.etg.migration.ui.theme.EtgIconButton
import org.etg.migration.ui.theme.EtgProgressBar
import org.etg.migration.ui.theme.EtgTextField
import org.etg.migration.ui.theme.EtgTone
import org.etg.migration.ui.theme.FormatUtils
import org.etg.migration.ui.theme.LocalGrapheneColors
import org.etg.migration.ui.theme.NumericStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionSheet(
    title: String,
    items: List<SelectableItem>,
    loading: Boolean,
    excluded: Set<String>,
    onToggle: (key: String, included: Boolean) -> Unit,
    onSetAll: (keys: List<String>, included: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onPreview: ((SelectableItem) -> Unit)? = null,
    showAvatars: Boolean = false
) {
    val g = LocalGrapheneColors.current
    var query by remember { mutableStateOf("") }
    val res = LocalResources.current
    val rows = remember(items, res.configuration) { items.map { displayRow(res, it) } }
    val filteredRows = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter {
            it.name.contains(query, true) || it.technical.orEmpty().contains(query, true) ||
                it.value.contains(query, true)
        }
    }
    val filtered = filteredRows.map { it.item }
    val includedCount = items.count { it.key !in excluded }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = g.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EtgDimens.screenGutter),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = g.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text("$includedCount / ${items.size}", style = NumericStyle, color = g.textSecondary)
            }
            if (loading) {
                EtgProgressBar(fraction = null)
            } else {
                EtgTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.sel_search),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EtgButton(
                        text = stringResource(R.string.action_select_all),
                        onClick = { onSetAll(filtered.map { it.key }, true) },
                        style = EtgButtonStyle.TONAL,
                        size = EtgButtonSize.SMALL,
                        modifier = Modifier.weight(1f)
                    )
                    EtgButton(
                        text = stringResource(R.string.action_select_none),
                        onClick = { onSetAll(filtered.map { it.key }, false) },
                        tone = EtgTone.NEUTRAL,
                        style = EtgButtonStyle.OUTLINE,
                        size = EtgButtonSize.SMALL,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (!loading && items.isEmpty()) {
            Text(
                text = stringResource(R.string.sel_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = g.textSecondary,
                modifier = Modifier.padding(EtgDimens.screenGutter)
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)

                .nestedScroll(KeepFlingInList),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
        ) {
            filteredRows.forEachIndexed { index, row ->
                val item = row.item
                if (item.groupRes != 0 && (index == 0 || filteredRows[index - 1].item.groupRes != item.groupRes)) {
                    item(key = "group_${item.groupRes}", contentType = "header") {
                        Text(
                            stringResource(item.groupRes).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = g.accentCyan,
                            modifier = Modifier.padding(
                                start = EtgDimens.screenGutter,
                                end = EtgDimens.screenGutter,
                                top = if (index == 0) 4.dp else 18.dp,
                                bottom = 6.dp
                            )
                        )
                    }
                }
                item(key = item.key, contentType = "row") {
                val included = item.key !in excluded
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(item.key, !included) }
                        .padding(start = 12.dp, end = EtgDimens.screenGutter, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EtgCheckbox(checked = included, onCheckedChange = null)
                    Spacer(Modifier.width(6.dp))
                    if (showAvatars) {
                        ItemAvatar(item, dimmed = !included)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            buildAnnotatedString {
                                append(row.name)
                                row.technical?.let { tech ->
                                    withStyle(SpanStyle(color = g.textTertiary, fontWeight = FontWeight.Normal, fontSize = 12.sp)) {
                                        append("  ($tech)")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (included) g.textPrimary else g.textTertiary,
                            maxLines = if (row.technical != null) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (row.value.isNotBlank() || item.needsShizuku) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (row.value.isNotBlank()) {
                                    Text(
                                        row.value,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (row.technical != null) g.textSecondary else g.textTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                                if (item.needsShizuku) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.set_needs_shizuku),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = g.accentCyan,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(EtgDimens.chipRadius))
                                            .background(g.accentContainer)
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                    val meta = listOfNotNull(
                        item.count.takeIf { it > 0 }?.toString(),
                        item.bytes.takeIf { it > 0 }?.let { FormatUtils.formatBytes(it) }
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(meta, style = NumericStyle, color = g.textSecondary)
                    }
                    if (onPreview != null && item.previewUris.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        EtgIconButton(
                            iconRes = R.drawable.ic_visibility,
                            onClick = { onPreview(item) },
                            tint = g.accentCyan,
                            size = 34.dp
                        )
                    }
                }
                EtgDivider(startIndent = 58.dp)
                }
            }
        }
    }
}

@Composable
fun FolderPreviewDialog(item: SelectableItem, onDismiss: () -> Unit) {
    val g = LocalGrapheneColors.current
    EtgDialog(onDismissRequest = onDismiss) {
        Text(item.title, style = MaterialTheme.typography.titleLarge, color = g.textPrimary)
        Text(
            "${item.subtitle} · ${item.count} · ${FormatUtils.formatBytes(item.bytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = g.textSecondary
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            items(item.previewUris) { uri -> Thumbnail(uri) }
        }
        EtgButton(
            text = stringResource(R.string.action_close),
            onClick = onDismiss,
            tone = EtgTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ItemAvatar(item: SelectableItem, dimmed: Boolean) {
    val g = LocalGrapheneColors.current
    val context = LocalContext.current
    val cacheKey = item.iconPackage ?: item.photoUri
    val image by produceState(cacheKey?.let { AvatarCache.get(it) }, cacheKey) {
        if (cacheKey == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    item.iconPackage != null ->
                        context.packageManager.getApplicationIcon(item.iconPackage).toBitmap(96, 96)
                    item.photoUri != null ->
                        context.contentResolver.openInputStream(Uri.parse(item.photoUri))?.use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                        }
                    else -> null
                }
            }.getOrNull()?.also { AvatarCache.put(cacheKey, it) }
        }
    }
    val palette = listOf(g.accentCyan, g.verifiedEmerald, g.warningAmber, g.errorRed, g.accentStrong)
    val tint = palette[(item.title.hashCode() and 0x7fffffff) % palette.size]
    val shape = if (item.iconPackage != null) RoundedCornerShape(10.dp) else CircleShape
    Box(
        modifier = Modifier
            .size(40.dp)
            .alpha(if (dimmed) 0.45f else 1f)
            .clip(shape)
            .background(if (item.iconPackage != null) Color.Transparent else tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = image
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else if (item.iconPackage == null) {
            val initials = item.title.split(' ', '-', '_')
                .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "#" }
            Text(initials, style = MaterialTheme.typography.titleSmall, color = tint)
        }
    }
}

@Composable
private fun Thumbnail(uri: Uri) {
    val g = LocalGrapheneColors.current
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(240, 240), null)
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
                    }
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(g.surfaceHigh)
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

private data class DisplayRow(val item: SelectableItem, val name: String, val technical: String?, val value: String)

private fun displayRow(res: android.content.res.Resources, item: SelectableItem): DisplayRow {
    if (item.titleRes == 0) return DisplayRow(item, item.title, null, item.subtitle)
    val def = item.settingId?.let { SettingsCatalog.def(it) }
    val value = if (def != null) SettingsCatalog.formatValue(res, def, item.subtitle) else item.subtitle
    return DisplayRow(item, res.getString(item.titleRes), item.title, value)
}

private object KeepFlingInList : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset.Zero else available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

private object AvatarCache : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
