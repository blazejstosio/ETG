package org.etg.migration.data.exporter

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.media.RingtoneManager
import android.provider.ContactsContract
import kotlinx.serialization.encodeToString
import org.etg.migration.codec.VCardCodec
import org.etg.migration.data.PermissionHelper
import org.etg.migration.data.archive.ArchiveSink
import org.etg.migration.data.archive.EtgJson
import org.etg.migration.model.CategoryImportStat
import org.etg.migration.model.CategoryScanInfo
import org.etg.migration.model.CategoryStatus
import org.etg.migration.model.ContactAddress
import org.etg.migration.model.ContactEmail
import org.etg.migration.model.ContactEvent
import org.etg.migration.model.ContactPhone
import org.etg.migration.model.ContactRecord
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.SoundRef
import java.nio.charset.StandardCharsets
import java.util.Base64

object ContactsExporter {
    fun scan(context: Context): CategoryScanInfo {
        if (!PermissionHelper.hasContactsRead(context)) {
            return CategoryScanInfo(
                category = ExportCategory.CONTACTS,
                status = CategoryStatus.NEEDS_PERMISSION,
                missingPermissions = listOf(Manifest.permission.READ_CONTACTS)
            )
        }
        var count = 0
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { cursor ->
                count = cursor.count
            }
        }
        return CategoryScanInfo(
            category = ExportCategory.CONTACTS,
            itemCount = count,
            estimatedBytes = count * 20_000L,
            status = if (count > 0) CategoryStatus.READY else CategoryStatus.EMPTY,
            detailSummary = "$count"
        )
    }

    fun export(
        context: Context,
        sink: ArchiveSink,
        excludedIds: Set<String> = emptySet(),
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        if (!PermissionHelper.hasContactsRead(context)) return 0
        val cr = context.contentResolver

        val groupNames = mutableMapOf<Long, String>()
        runCatching {
            cr.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
                "${ContactsContract.Groups.DELETED} = 0",
                null,
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.Groups._ID)
                val titleIdx = c.getColumnIndex(ContactsContract.Groups.TITLE)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val title = c.getString(titleIdx) ?: continue
                    if (!title.startsWith("Starred in Android", ignoreCase = true) &&
                        !title.equals("My Contacts", ignoreCase = true)
                    ) {
                        groupNames[id] = title
                    }
                }
            }
        }

        class MutableContact(val id: Long, var displayName: String) {
            var givenName = ""
            var familyName = ""
            var middleName = ""
            var prefix = ""
            var suffix = ""
            var org = ""
            var title = ""
            var note = ""
            val phones = linkedSetOf<ContactPhone>()
            val emails = linkedSetOf<ContactEmail>()
            val addresses = mutableListOf<ContactAddress>()
            val events = mutableListOf<ContactEvent>()
            val websites = linkedSetOf<String>()
            val groups = linkedSetOf<String>()
            var starred = false
            var sendToVoicemail = false
            var customRingtone: String? = null
        }

        val contactsMap = linkedMapOf<Long, MutableContact>()
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.SEND_TO_VOICEMAIL,
                ContactsContract.Contacts.CUSTOM_RINGTONE
            ),
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val starIdx = c.getColumnIndex(ContactsContract.Contacts.STARRED)
            val vmIdx = c.getColumnIndex(ContactsContract.Contacts.SEND_TO_VOICEMAIL)
            val ringIdx = c.getColumnIndex(ContactsContract.Contacts.CUSTOM_RINGTONE)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                if (id.toString() in excludedIds) continue
                val name = c.getString(nameIdx).orEmpty()
                contactsMap[id] = MutableContact(id, name).apply {
                    starred = starIdx >= 0 && c.getInt(starIdx) == 1
                    sendToVoicemail = vmIdx >= 0 && c.getInt(vmIdx) == 1
                    customRingtone = if (ringIdx >= 0) c.getString(ringIdx) else null
                }
            }
        }

        if (contactsMap.isEmpty()) return 0

        val dataProjection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10
        )

        cr.query(ContactsContract.Data.CONTENT_URI, dataProjection, null, null, null)?.use { c ->
            val cidIdx = c.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val mimeIdx = c.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val d1 = c.getColumnIndex(ContactsContract.Data.DATA1)
            val d2 = c.getColumnIndex(ContactsContract.Data.DATA2)
            val d3 = c.getColumnIndex(ContactsContract.Data.DATA3)
            val d4 = c.getColumnIndex(ContactsContract.Data.DATA4)
            val d5 = c.getColumnIndex(ContactsContract.Data.DATA5)
            val d6 = c.getColumnIndex(ContactsContract.Data.DATA6)
            val d7 = c.getColumnIndex(ContactsContract.Data.DATA7)
            val d8 = c.getColumnIndex(ContactsContract.Data.DATA8)
            val d9 = c.getColumnIndex(ContactsContract.Data.DATA9)
            val d10 = c.getColumnIndex(ContactsContract.Data.DATA10)

            while (c.moveToNext()) {
                val contact = contactsMap[c.getLong(cidIdx)] ?: continue
                when (c.getString(mimeIdx)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        if (contact.givenName.isEmpty()) contact.givenName = c.getString(d2).orEmpty()
                        if (contact.familyName.isEmpty()) contact.familyName = c.getString(d3).orEmpty()
                        if (contact.prefix.isEmpty()) contact.prefix = c.getString(d4).orEmpty()
                        if (contact.middleName.isEmpty()) contact.middleName = c.getString(d5).orEmpty()
                        if (contact.suffix.isEmpty()) contact.suffix = c.getString(d6).orEmpty()
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val num = c.getString(d1)?.trim().orEmpty()
                        if (num.isNotEmpty()) {
                            contact.phones += ContactPhone(
                                number = num,
                                type = c.getInt(d2),
                                label = c.getString(d3)
                            )
                        }
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val addr = c.getString(d1)?.trim().orEmpty()
                        if (addr.isNotEmpty()) {
                            contact.emails += ContactEmail(
                                address = addr,
                                type = c.getInt(d2),
                                label = c.getString(d3)
                            )
                        }
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val formatted = c.getString(d1).orEmpty()
                        val street = c.getString(d4).orEmpty()
                        val city = c.getString(d7).orEmpty()
                        val region = c.getString(d8).orEmpty()
                        val postcode = c.getString(d9).orEmpty()
                        val country = c.getString(d10).orEmpty()
                        if (formatted.isNotBlank() || street.isNotBlank() || city.isNotBlank()) {
                            contact.addresses += ContactAddress(
                                formatted = formatted,
                                street = street,
                                city = city,
                                region = region,
                                postalCode = postcode,
                                country = country,
                                type = c.getInt(d2),
                                label = c.getString(d3)
                            )
                        }
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        if (contact.org.isEmpty()) contact.org = c.getString(d1).orEmpty()
                        if (contact.title.isEmpty()) contact.title = c.getString(d4).orEmpty()
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        val n = c.getString(d1).orEmpty()
                        if (n.isNotBlank() && contact.note.isEmpty()) contact.note = n
                    }
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> {
                        val u = c.getString(d1)?.trim().orEmpty()
                        if (u.isNotEmpty()) contact.websites += u
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        val start = c.getString(d1)?.trim().orEmpty()
                        if (start.isNotEmpty()) {
                            contact.events += ContactEvent(
                                startDate = start,
                                type = c.getInt(d2),
                                label = c.getString(d3)
                            )
                        }
                    }
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> {
                        val gid = c.getLong(d1)
                        groupNames[gid]?.let { contact.groups += it }
                    }
                }
            }
        }

        val total = contactsMap.size
        val records = ArrayList<ContactRecord>(total)
        val vcardRecords = ArrayList<ContactRecord>(total)
        var idx = 0

        for ((id, mc) in contactsMap) {
            idx++
            onItemProgress(idx, total, mc.displayName.ifBlank { "Contact #$id" })

            val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
            val photoBytes = runCatching {
                ContactsContract.Contacts.openContactPhotoInputStream(cr, contactUri, true)?.use { it.readBytes() }
            }.getOrNull()

            var photoPath: String? = null
            var photoB64: String? = null
            if (photoBytes != null && photoBytes.isNotEmpty()) {
                photoPath = "contacts/photos/$id.jpg"
                sink.writeBytes(photoPath, ExportCategory.CONTACTS, photoBytes, compress = false)
                photoB64 = Base64.getEncoder().encodeToString(photoBytes)
            }

            val baseRecord = ContactRecord(
                id = id,
                displayName = mc.displayName,
                givenName = mc.givenName,
                familyName = mc.familyName,
                middleName = mc.middleName,
                prefix = mc.prefix,
                suffix = mc.suffix,
                organization = mc.org,
                jobTitle = mc.title,
                note = mc.note,
                phones = mc.phones.toList(),
                emails = mc.emails.toList(),
                addresses = mc.addresses.toList(),
                events = mc.events.toList(),
                websites = mc.websites.toList(),
                groups = mc.groups.toList(),
                photoPath = photoPath,
                photoBase64 = null,
                starred = mc.starred,
                sendToVoicemail = mc.sendToVoicemail,
                customRingtone = mc.customRingtone?.let { SoundRefResolver.describe(context, it) }
            )
            records += baseRecord
            vcardRecords += baseRecord.copy(photoBase64 = photoB64)
        }

        val jsonStr = EtgJson.encodeToString(records)
        sink.writeBytes(
            "contacts/contacts.json",
            ExportCategory.CONTACTS,
            jsonStr.toByteArray(StandardCharsets.UTF_8),
            compress = true
        )

        val vcfStr = VCardCodec.encodeAll(vcardRecords)
        sink.writeBytes(
            "contacts/contacts.vcf",
            ExportCategory.CONTACTS,
            vcfStr.toByteArray(StandardCharsets.UTF_8),
            compress = true
        )

        return records.size
    }

    fun import(
        context: Context,
        jsonBytes: ByteArray?,
        vcfBytes: ByteArray?,
        photoResolver: (String) -> ByteArray?,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): CategoryImportStat {
        if (!PermissionHelper.hasContactsWrite(context)) {
            return CategoryImportStat(
                category = ExportCategory.CONTACTS,
                notes = "Missing WRITE_CONTACTS permission"
            )
        }

        val contacts: List<ContactRecord> = when {
            jsonBytes != null -> runCatching {
                EtgJson.decodeFromString<List<ContactRecord>>(String(jsonBytes, StandardCharsets.UTF_8))
            }.getOrElse { emptyList() }
            vcfBytes != null -> VCardCodec.decodeAll(String(vcfBytes, StandardCharsets.UTF_8))
            else -> emptyList()
        }

        if (contacts.isEmpty()) {
            return CategoryImportStat(ExportCategory.CONTACTS, notes = "No contacts in archive")
        }

        val cr = context.contentResolver
        val existingSignatures = hashSetOf<String>()
        if (PermissionHelper.hasContactsRead(context)) {
            runCatching {
                cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null,
                    null,
                    null
                )?.use { c ->
                    val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val n = normalizeName(c.getString(nameIdx).orEmpty())
                        val p = normalizePhone(c.getString(numIdx).orEmpty())
                        if (p.isNotEmpty()) existingSignatures += "$n|$p"
                    }
                }
                cr.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    null,
                    null,
                    null
                )?.use { c ->
                    val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                    while (c.moveToNext()) {
                        val n = normalizeName(c.getString(nameIdx).orEmpty())
                        if (n.isNotEmpty()) existingSignatures += "NAME|$n"
                    }
                }
            }
        }

        var imported = 0
        var skipped = 0
        var failed = 0
        val total = contacts.size
        val ringtoneCache = hashMapOf<SoundRef, String?>()

        for ((index, contact) in contacts.withIndex()) {
            onItemProgress(index + 1, total, contact.displayName.ifBlank { "Contact #${index + 1}" })

            val normName = normalizeName(contact.displayName)
            val isDup = if (contact.phones.isNotEmpty()) {
                contact.phones.any { phone ->
                    val np = normalizePhone(phone.number)
                    np.isNotEmpty() && existingSignatures.contains("$normName|$np")
                }
            } else {
                normName.isNotEmpty() && existingSignatures.contains("NAME|$normName")
            }

            if (isDup) {
                skipped++
                continue
            }

            val ops = ArrayList<ContentProviderOperation>()
            val rawIndex = 0
            val ringtoneUri = contact.customRingtone?.let { ref ->
                ringtoneCache.getOrPut(ref) { SoundRefResolver.resolve(context, ref, RingtoneManager.TYPE_RINGTONE) }
            }
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .withValue(ContactsContract.RawContacts.STARRED, if (contact.starred) 1 else 0)
                .withValue(ContactsContract.RawContacts.SEND_TO_VOICEMAIL, if (contact.sendToVoicemail) 1 else 0)
                .apply { if (!ringtoneUri.isNullOrEmpty()) withValue(ContactsContract.RawContacts.CUSTOM_RINGTONE, ringtoneUri) }
                .build()

            if (contact.displayName.isNotBlank() || contact.givenName.isNotBlank() || contact.familyName.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, contact.givenName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, contact.familyName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, contact.middleName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, contact.prefix)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, contact.suffix)
                    .build()
            }

            for (phone in contact.phones) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phone.type)
                    .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, phone.label)
                    .build()
            }

            for (email in contact.emails) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.address)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, email.type)
                    .withValue(ContactsContract.CommonDataKinds.Email.LABEL, email.label)
                    .build()
            }

            for (adr in contact.addresses) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, adr.formatted)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, adr.street)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, adr.city)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, adr.region)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, adr.postalCode)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, adr.country)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, adr.type)
                    .build()
            }

            if (contact.organization.isNotBlank() || contact.jobTitle.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, contact.organization)
                    .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, contact.jobTitle)
                    .build()
            }

            if (contact.note.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, contact.note)
                    .build()
            }

            for (url in contact.websites) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Website.URL, url)
                    .build()
            }

            for (ev in contact.events) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, ev.startDate)
                    .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ev.type)
                    .build()
            }

            val photoData = contact.photoPath?.let { photoResolver(it) }
                ?: contact.photoBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            if (photoData != null && photoData.isNotEmpty() && photoData.size < 450_000) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoData)
                    .build()
            }

            runCatching {
                cr.applyBatch(ContactsContract.AUTHORITY, ops)
                imported++
                if (normName.isNotEmpty()) existingSignatures += "NAME|$normName"
                contact.phones.forEach {
                    val np = normalizePhone(it.number)
                    if (np.isNotEmpty()) existingSignatures += "$normName|$np"
                }
            }.onFailure {
                failed++
            }
        }

        val starredCount = contacts.count { it.starred }
        val ringtoneCount = ringtoneCache.values.count { !it.isNullOrEmpty() }
        return CategoryImportStat(
            category = ExportCategory.CONTACTS,
            importedCount = imported,
            skippedDuplicates = skipped,
            failedCount = failed,
            notes = listOfNotNull(
                "★ $starredCount".takeIf { starredCount > 0 },
                "♪ $ringtoneCount".takeIf { ringtoneCount > 0 }
            ).joinToString(" · ")
        )
    }

    private fun normalizeName(s: String): String = s.trim().lowercase()

    private fun normalizePhone(s: String): String {
        val digits = s.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
