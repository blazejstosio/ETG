package org.etg.migration.data.exporter

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import org.etg.migration.R
import org.etg.migration.data.PermissionHelper
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.SystemSettingsSnapshot

data class SelectableItem(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val count: Int = 0,
    val bytes: Long = 0L,

    val previewUris: List<Uri> = emptyList(),

    val iconPackage: String? = null,

    val photoUri: String? = null,

    val titleRes: Int = 0,

    val groupRes: Int = 0,

    val settingId: String? = null,

    val needsShizuku: Boolean = false
)

object SelectionCatalog {
    val SELECTABLE = setOf(
        ExportCategory.CONTACTS,
        ExportCategory.MESSAGES_SMS_MMS,
        ExportCategory.CALENDAR,
        ExportCategory.PHOTOS_VIDEOS,
        ExportCategory.AUDIO_RINGTONES,
        ExportCategory.DOCUMENTS_DOWNLOADS,
        ExportCategory.APPS_AND_APKS,
        ExportCategory.SYSTEM_SETTINGS
    )

    val WITH_AVATARS = setOf(ExportCategory.CONTACTS, ExportCategory.MESSAGES_SMS_MMS, ExportCategory.APPS_AND_APKS)

    fun load(
        context: Context,
        category: ExportCategory,
        includeVideos: Boolean,
        extraSafFolders: List<String> = emptyList()
    ): List<SelectableItem> = when (category) {
        ExportCategory.CONTACTS -> contacts(context)
        ExportCategory.MESSAGES_SMS_MMS -> conversations(context)
        ExportCategory.CALENDAR -> CalendarExporter.listEvents(context)
        ExportCategory.PHOTOS_VIDEOS -> mediaFolders(context, includeVideos)
        ExportCategory.AUDIO_RINGTONES -> MediaAndFilesExporter.listAudio(context)
        ExportCategory.DOCUMENTS_DOWNLOADS -> MediaAndFilesExporter.listDocuments(context, extraSafFolders)
        ExportCategory.APPS_AND_APKS -> AppsExporter.scan(context, includeApks = true).second.map {
            SelectableItem(
                key = it.packageName,
                title = it.label,
                subtitle = it.packageName + if (it.versionName.isNotBlank()) " · v${it.versionName}" else "",
                bytes = it.totalBytes,
                iconPackage = it.packageName
            )
        }
        ExportCategory.SYSTEM_SETTINGS -> settingsItems(SettingsExporter.collectSnapshot(context).first)
        else -> emptyList()
    }

    fun addressKey(address: String): String {
        val digits = address.filter { it.isDigit() }
        return if (digits.length >= 5) digits.takeLast(9) else address.trim().lowercase()
    }

    fun mediaFolderKey(relativePath: String): String = relativePath.trim('/')

    const val SETTING_WALLPAPER = "wallpaper"
    const val SETTING_DICTIONARY = "dictionary"
    const val SETTING_RINGTONE = "sound_ringtone"
    const val SETTING_NOTIFICATION = "sound_notification"
    const val SETTING_ALARM = "sound_alarm"

    fun filterSnapshot(s: SystemSettingsSnapshot, excluded: Set<String>): SystemSettingsSnapshot {
        if (excluded.isEmpty()) return s
        return s.copy(
            writableSystemSettings = s.writableSystemSettings.filterKeys { it !in excluded },
            personalDictionaryWords = if (SETTING_DICTIONARY in excluded) emptyList() else s.personalDictionaryWords,
            hasWallpaper = s.hasWallpaper && SETTING_WALLPAPER !in excluded,
            ringtone = s.ringtone.takeIf { SETTING_RINGTONE !in excluded },
            notificationSound = s.notificationSound.takeIf { SETTING_NOTIFICATION !in excluded },
            alarmSound = s.alarmSound.takeIf { SETTING_ALARM !in excluded },
            extendedSettings = s.extendedSettings.filterKeys { it !in excluded }
        )
    }

    private fun contacts(context: Context): List<SelectableItem> {
        if (!PermissionHelper.hasContactsRead(context)) return emptyList()
        val phones = hashMapOf<Long, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id !in phones) phones[id] = c.getString(1).orEmpty()
                }
            }
        }
        val out = mutableListOf<SelectableItem>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
                ),
                null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += SelectableItem(
                        key = id.toString(),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "#$id",
                        subtitle = phones[id].orEmpty(),
                        photoUri = c.getString(2)
                    )
                }
            }
        }
        return out
    }

    private fun conversations(context: Context): List<SelectableItem> {
        if (!PermissionHelper.hasSmsRead(context)) return emptyList()
        data class Acc(var address: String, var count: Int, var last: Long, var snippet: String)
        val byKey = linkedMapOf<String, Acc>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY),
                null, null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0).orEmpty()
                    if (addr.isBlank()) continue
                    val acc = byKey.getOrPut(addressKey(addr)) {
                        Acc(addr, 0, c.getLong(1), c.getString(2).orEmpty().take(60))
                    }
                    acc.count++
                }
            }
        }
        return byKey.map { (key, acc) ->
            val (name, photo) = lookupContact(context, acc.address)
            SelectableItem(
                key = key,
                title = name ?: acc.address,
                subtitle = (if (name != null) acc.address + " · " else "") + acc.snippet.replace('\n', ' '),
                count = acc.count,
                photoUri = photo
            )
        }
    }

    private fun lookupContact(context: Context, address: String): Pair<String?, String?> {
        if (!PermissionHelper.hasContactsRead(context) || address.none { it.isDigit() }) return null to null
        return runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
        }.getOrNull() ?: (null to null)
    }

    private fun mediaFolders(context: Context, includeVideos: Boolean): List<SelectableItem> {
        data class Acc(var count: Int = 0, var bytes: Long = 0L, val previews: MutableList<Uri> = mutableListOf())
        val folders = sortedMapOf<String, Acc>(String.CASE_INSENSITIVE_ORDER)
        fun scan(collection: Uri, fallback: String) {
            val projection = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection += MediaStore.MediaColumns.RELATIVE_PATH
            } else {
                @Suppress("DEPRECATION")
                projection += MediaStore.MediaColumns.DATA
            }
            runCatching {
                context.contentResolver.query(
                    collection, projection.toTypedArray(), null, null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { c ->
                    val root = android.os.Environment.getExternalStorageDirectory().absolutePath
                    while (c.moveToNext()) {
                        val raw = c.getString(2).orEmpty()
                        val rel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) raw
                        else raw.removePrefix(root).trimStart('/').substringBeforeLast('/', "")
                        val acc = folders.getOrPut(mediaFolderKey(rel).ifBlank { fallback }) { Acc() }
                        acc.count++
                        acc.bytes += c.getLong(1).coerceAtLeast(0L)
                        if (acc.previews.size < 60) acc.previews += ContentUris.withAppendedId(collection, c.getLong(0))
                    }
                }
            }
        }
        if (PermissionHelper.hasImagesRead(context)) scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/ETG")
        if (includeVideos && PermissionHelper.hasVideosRead(context)) scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/ETG")
        return folders.map { (path, acc) ->
            SelectableItem(
                key = path,
                title = path.substringAfterLast('/').ifBlank { path },
                subtitle = path,
                count = acc.count,
                bytes = acc.bytes,
                previewUris = acc.previews
            )
        }.sortedByDescending { it.count }
    }

    internal fun settingsItems(s: SystemSettingsSnapshot): List<SelectableItem> {
        val rows = mutableListOf<Pair<SettingsCatalog.Group, SelectableItem>>()
        if (s.hasWallpaper) {
            rows += SettingsCatalog.Group.APPEARANCE to SelectableItem(
                SETTING_WALLPAPER, "wallpaper", "PNG",
                titleRes = R.string.set_wallpaper, groupRes = SettingsCatalog.Group.APPEARANCE.titleRes
            )
        }
        fun sound(key: String, ref: org.etg.migration.model.SoundRef?, label: Int, tech: String) {
            ref ?: return
            rows += SettingsCatalog.Group.SOUNDS to SelectableItem(
                key, tech, ref.title.ifBlank { ref.displayName },
                titleRes = label, groupRes = SettingsCatalog.Group.SOUNDS.titleRes
            )
        }
        sound(SETTING_RINGTONE, s.ringtone, R.string.set_ringtone, "ringtone")
        sound(SETTING_NOTIFICATION, s.notificationSound, R.string.set_notification_sound, "notification_sound")
        sound(SETTING_ALARM, s.alarmSound, R.string.set_alarm_sound, "alarm_alert")
        val values = s.writableSystemSettings + s.extendedSettings
        for (def in SettingsCatalog.DEFS) {
            val v = values[def.id] ?: continue
            rows += def.group to SelectableItem(
                key = def.id,
                title = def.key,
                subtitle = v,
                titleRes = def.labelRes,
                groupRes = def.group.titleRes,
                settingId = def.id,
                needsShizuku = def.needsShizuku
            )
        }
        if (s.personalDictionaryWords.isNotEmpty()) {
            rows += SettingsCatalog.Group.KEYBOARD to SelectableItem(
                SETTING_DICTIONARY,
                "user_dictionary",
                s.personalDictionaryWords.take(6).joinToString(),
                count = s.personalDictionaryWords.size,
                titleRes = R.string.set_dictionary,
                groupRes = SettingsCatalog.Group.KEYBOARD.titleRes
            )
        }

        return rows.sortedBy { it.first.ordinal }.map { it.second }
    }
}
