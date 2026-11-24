package org.etg.migration.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.etg.migration.R
import org.etg.migration.model.ExportCategory

data class CategoryUiMeta(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
    @param:DrawableRes val iconRes: Int
)

fun ExportCategory.uiMeta(): CategoryUiMeta = when (this) {
    ExportCategory.CONTACTS -> CategoryUiMeta(R.string.cat_contacts_title, R.string.cat_contacts_desc, R.drawable.ic_contacts)
    ExportCategory.MESSAGES_SMS_MMS -> CategoryUiMeta(R.string.cat_sms_title, R.string.cat_sms_desc, R.drawable.ic_sms)
    ExportCategory.CALL_LOG -> CategoryUiMeta(R.string.cat_calls_title, R.string.cat_calls_desc, R.drawable.ic_call)
    ExportCategory.CHAT_BACKUPS -> CategoryUiMeta(R.string.cat_chats_title, R.string.cat_chats_desc, R.drawable.ic_chat)
    ExportCategory.CALENDAR -> CategoryUiMeta(R.string.cat_calendar_title, R.string.cat_calendar_desc, R.drawable.ic_calendar)
    ExportCategory.PHOTOS_VIDEOS -> CategoryUiMeta(R.string.cat_photos_title, R.string.cat_photos_desc, R.drawable.ic_photo)
    ExportCategory.AUDIO_RINGTONES -> CategoryUiMeta(R.string.cat_audio_title, R.string.cat_audio_desc, R.drawable.ic_audio)
    ExportCategory.DOCUMENTS_DOWNLOADS -> CategoryUiMeta(R.string.cat_docs_title, R.string.cat_docs_desc, R.drawable.ic_folder)
    ExportCategory.VAULT_KEYS_2FA -> CategoryUiMeta(R.string.cat_vault_title, R.string.cat_vault_desc, R.drawable.ic_key)
    ExportCategory.WIFI_NETWORKS -> CategoryUiMeta(R.string.cat_wifi_title, R.string.cat_wifi_desc, R.drawable.ic_wifi)
    ExportCategory.APPS_AND_APKS -> CategoryUiMeta(R.string.cat_apps_title, R.string.cat_apps_desc, R.drawable.ic_apps)
    ExportCategory.SYSTEM_SETTINGS -> CategoryUiMeta(R.string.cat_settings_title, R.string.cat_settings_desc, R.drawable.ic_tune)
}
