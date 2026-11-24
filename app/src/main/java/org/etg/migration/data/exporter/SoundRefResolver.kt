package org.etg.migration.data.exporter

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.etg.migration.model.SoundRef

object SoundRefResolver {
    fun describe(context: Context, uriString: String?): SoundRef? {
        if (uriString == null) return null
        if (uriString.isBlank()) return SoundRef(silent = true)
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val cr = context.contentResolver
        val fromStore = runCatching {
            cr.query(
                uri,
                arrayOf(MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val t = c.getColumnIndex(MediaStore.MediaColumns.TITLE)
                    val d = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    SoundRef(
                        title = if (t >= 0) c.getString(t).orEmpty() else "",
                        displayName = if (d >= 0) c.getString(d).orEmpty() else ""
                    )
                } else null
            }
        }.getOrNull()
        if (fromStore != null && (fromStore.title.isNotBlank() || fromStore.displayName.isNotBlank())) {
            return fromStore
        }
        val title = runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
        return title?.takeIf { it.isNotBlank() }?.let { SoundRef(title = it) }
    }

    fun describeDefault(context: Context, type: Int): SoundRef? {
        val uri = runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, type) }.getOrNull()
        return if (uri == null) SoundRef(silent = true) else describe(context, uri.toString())
    }

    fun resolve(context: Context, ref: SoundRef?, type: Int = RingtoneManager.TYPE_ALL): String? {
        if (ref == null) return null
        if (ref.silent) return ""
        findInMediaStore(context, ref)?.let { return it.toString() }
        return findSystemTone(context, ref, type)?.toString()
    }

    fun applyDefault(context: Context, type: Int, ref: SoundRef?): Boolean {
        val resolved = resolve(context, ref, type) ?: return false
        return runCatching {
            RingtoneManager.setActualDefaultRingtoneUri(
                context,
                type,
                resolved.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            )
            true
        }.getOrDefault(false)
    }

    private fun findInMediaStore(context: Context, ref: SoundRef): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val (selection, args) = when {
            ref.displayName.isNotBlank() -> "${MediaStore.MediaColumns.DISPLAY_NAME} = ?" to arrayOf(ref.displayName)
            ref.title.isNotBlank() -> "${MediaStore.MediaColumns.TITLE} = ?" to arrayOf(ref.title)
            else -> return null
        }
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                null
            )?.use { c ->
                if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null
            }
        }.getOrNull()
    }

    private fun findSystemTone(context: Context, ref: SoundRef, type: Int): Uri? {
        val wanted = ref.title.ifBlank { ref.displayName.substringBeforeLast('.') }.trim()
        if (wanted.isEmpty()) return null
        return runCatching {
            val manager = RingtoneManager(context).apply { setType(type) }
            val cursor = manager.cursor
            var match: Uri? = null
            while (cursor.moveToNext()) {
                val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX).orEmpty()
                if (title.equals(wanted, ignoreCase = true)) {
                    match = manager.getRingtoneUri(cursor.position)
                    break
                }
            }
            match
        }.getOrNull()
    }
}
