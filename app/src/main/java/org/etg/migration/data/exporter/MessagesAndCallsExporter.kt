package org.etg.migration.data.exporter

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.Telephony
import android.util.Xml
import kotlinx.serialization.encodeToString
import org.etg.migration.data.PermissionHelper
import org.etg.migration.data.archive.ArchiveSink
import org.etg.migration.data.archive.EtgJson
import org.etg.migration.model.BlockedNumberRecord
import org.etg.migration.model.CallLogRecord
import org.etg.migration.model.CategoryImportStat
import org.etg.migration.model.CategoryScanInfo
import org.etg.migration.model.CategoryStatus
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.MmsAddressRecord
import org.etg.migration.model.MmsPartRecord
import org.etg.migration.model.MmsRecord
import org.etg.migration.model.SmsMmsBackup
import org.etg.migration.model.SmsRecord
import java.io.StringWriter
import java.nio.charset.StandardCharsets

object MessagesAndCallsExporter {
    private val MMS_ADDR_URI_TEMPLATE = "content://mms/%d/addr"
    private val MMS_PART_URI = Uri.parse("content://mms/part")
    const val BLOCKED_NUMBERS_PATH = "messages/blocked_numbers.json"

    fun scanSmsMms(context: Context): CategoryScanInfo {
        if (!PermissionHelper.hasSmsRead(context)) {
            return CategoryScanInfo(
                category = ExportCategory.MESSAGES_SMS_MMS,
                status = CategoryStatus.NEEDS_PERMISSION,
                missingPermissions = listOf(Manifest.permission.READ_SMS)
            )
        }
        var smsCount = 0
        var mmsCount = 0
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null,
                null,
                null
            )?.use { smsCount = it.count }
        }
        runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID),
                null,
                null,
                null
            )?.use { mmsCount = it.count }
        }
        val total = smsCount + mmsCount
        return CategoryScanInfo(
            category = ExportCategory.MESSAGES_SMS_MMS,
            itemCount = total,
            estimatedBytes = smsCount * 600L + mmsCount * 120_000L,
            status = if (total > 0) CategoryStatus.READY else CategoryStatus.EMPTY,
            detailSummary = buildString {
                append("SMS: $smsCount • MMS: $mmsCount")
                readBlockedNumbers(context)?.let { if (it.isNotEmpty()) append(" • ⛔ ${it.size}") }
            }
        )
    }

    fun readBlockedNumbers(context: Context): List<BlockedNumberRecord>? {
        if (!PermissionHelper.isDefaultSmsApp(context)) return null
        if (!runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)) return null
        return runCatching {
            val out = linkedSetOf<String>()
            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null,
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                }
            }
            out.map { BlockedNumberRecord(it) }
        }.getOrNull()
    }

    fun exportBlockedNumbers(context: Context, sink: ArchiveSink): Int {
        val numbers = readBlockedNumbers(context) ?: return 0
        if (numbers.isEmpty()) return 0
        sink.writeBytes(
            BLOCKED_NUMBERS_PATH,
            ExportCategory.MESSAGES_SMS_MMS,
            EtgJson.encodeToString(numbers).toByteArray(StandardCharsets.UTF_8),
            compress = true
        )
        return numbers.size
    }

    fun importBlockedNumbers(context: Context, jsonBytes: ByteArray?): Pair<CategoryImportStat, Boolean>? {
        if (jsonBytes == null) return null
        val numbers = runCatching {
            EtgJson.decodeFromString<List<BlockedNumberRecord>>(String(jsonBytes, StandardCharsets.UTF_8))
        }.getOrNull().orEmpty()
        if (numbers.isEmpty()) return null

        val existing = readBlockedNumbers(context)
            ?: return CategoryImportStat(
                category = ExportCategory.MESSAGES_SMS_MMS,
                notes = "Blocked numbers (${numbers.size}): default SMS role required"
            ) to true

        val known = existing.map { normalizeNumber(it.number) }.toHashSet()
        var added = 0
        var skipped = 0
        var failed = 0
        for (rec in numbers) {
            if (!known.add(normalizeNumber(rec.number))) {
                skipped++
                continue
            }
            runCatching {
                val cv = ContentValues().apply {
                    put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, rec.number)
                }
                context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, cv)
                    ?: error("insert returned null")
                added++
            }.onFailure { failed++ }
        }
        return CategoryImportStat(
            category = ExportCategory.MESSAGES_SMS_MMS,
            importedCount = added,
            skippedDuplicates = skipped,
            failedCount = failed,
            notes = "Blocked numbers"
        ) to false
    }

    private fun normalizeNumber(n: String): String {
        val digits = n.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    fun scanCallLog(context: Context): CategoryScanInfo {
        if (!PermissionHelper.hasCallLogRead(context)) {
            return CategoryScanInfo(
                category = ExportCategory.CALL_LOG,
                status = CategoryStatus.NEEDS_PERMISSION,
                missingPermissions = listOf(Manifest.permission.READ_CALL_LOG)
            )
        }
        var count = 0
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null,
                null,
                null
            )?.use { count = it.count }
        }
        return CategoryScanInfo(
            category = ExportCategory.CALL_LOG,
            itemCount = count,
            estimatedBytes = count * 300L,
            status = if (count > 0) CategoryStatus.READY else CategoryStatus.EMPTY,
            detailSummary = "$count"
        )
    }

    fun exportSmsMms(
        context: Context,
        sink: ArchiveSink,
        excludedAddresses: Set<String> = emptySet(),
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        if (!PermissionHelper.hasSmsRead(context)) return 0
        val cr = context.contentResolver
        val smsList = mutableListOf<SmsRecord>()

        runCatching {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.DATE_SENT,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.READ,
                    Telephony.Sms.SEEN,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.SUBJECT
                ),
                null,
                null,
                "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                val total = c.count
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val dateSentIdx = c.getColumnIndex(Telephony.Sms.DATE_SENT)
                val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                val readIdx = c.getColumnIndex(Telephony.Sms.READ)
                val seenIdx = c.getColumnIndex(Telephony.Sms.SEEN)
                val threadIdx = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                val subIdx = c.getColumnIndex(Telephony.Sms.SUBJECT)

                var count = 0
                while (c.moveToNext()) {
                    count++
                    val addr = c.getString(addrIdx).orEmpty()
                    if (excludedAddresses.isNotEmpty() && SelectionCatalog.addressKey(addr) in excludedAddresses) continue
                    if (count % 25 == 0 || count == total) {
                        onItemProgress(count, total, "SMS: $addr")
                    }
                    smsList += SmsRecord(
                        address = addr,
                        body = c.getString(bodyIdx).orEmpty(),
                        dateMs = c.getLong(dateIdx),
                        dateSentMs = if (dateSentIdx >= 0) c.getLong(dateSentIdx) else 0L,
                        type = c.getInt(typeIdx),
                        read = c.getInt(readIdx),
                        seen = if (seenIdx >= 0) c.getInt(seenIdx) else 1,
                        threadId = if (threadIdx >= 0) c.getLong(threadIdx) else 0L,
                        subject = if (subIdx >= 0) c.getString(subIdx) else null
                    )
                }
            }
        }

        val mmsList = mutableListOf<MmsRecord>()
        runCatching {
            cr.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(
                    Telephony.Mms._ID,
                    Telephony.Mms.DATE,
                    Telephony.Mms.MESSAGE_BOX,
                    Telephony.Mms.READ,
                    Telephony.Mms.SUBJECT
                ),
                null,
                null,
                "${Telephony.Mms.DATE} ASC"
            )?.use { c ->
                val idIdx = c.getColumnIndex(Telephony.Mms._ID)
                val dateIdx = c.getColumnIndex(Telephony.Mms.DATE)
                val boxIdx = c.getColumnIndex(Telephony.Mms.MESSAGE_BOX)
                val readIdx = c.getColumnIndex(Telephony.Mms.READ)
                val subIdx = c.getColumnIndex(Telephony.Mms.SUBJECT)

                while (c.moveToNext()) {
                    val mmsId = c.getLong(idIdx)
                    val addresses = queryMmsAddresses(context, mmsId)
                    if (excludedAddresses.isNotEmpty() &&
                        addresses.any { SelectionCatalog.addressKey(it.address) in excludedAddresses }
                    ) continue
                    val parts = queryAndExportMmsParts(context, mmsId, sink)
                    mmsList += MmsRecord(
                        id = mmsId,
                        dateSec = c.getLong(dateIdx),
                        msgBox = c.getInt(boxIdx),
                        read = c.getInt(readIdx),
                        subject = if (subIdx >= 0) c.getString(subIdx) else null,
                        addresses = addresses,
                        parts = parts
                    )
                }
            }
        }

        val backup = SmsMmsBackup(sms = smsList, mms = mmsList)
        val jsonBytes = EtgJson.encodeToString(backup).toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("messages/sms_mms.json", ExportCategory.MESSAGES_SMS_MMS, jsonBytes, compress = true)

        val xmlBytes = serializeSmsBackupXml(smsList).toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("messages/sms_backup.xml", ExportCategory.MESSAGES_SMS_MMS, xmlBytes, compress = true)

        return smsList.size + mmsList.size
    }

    private fun queryMmsAddresses(context: Context, mmsId: Long): List<MmsAddressRecord> {
        val list = mutableListOf<MmsAddressRecord>()
        runCatching {
            val uri = Uri.parse(MMS_ADDR_URI_TEMPLATE.format(mmsId))
            context.contentResolver.query(
                uri,
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                null,
                null,
                null
            )?.use { c ->
                val aIdx = c.getColumnIndex(Telephony.Mms.Addr.ADDRESS)
                val tIdx = c.getColumnIndex(Telephony.Mms.Addr.TYPE)
                while (c.moveToNext()) {
                    val addr = c.getString(aIdx).orEmpty()
                    if (addr.isNotBlank() && addr != "insert-address-token") {
                        list += MmsAddressRecord(address = addr, type = c.getInt(tIdx))
                    }
                }
            }
        }
        return list
    }

    private fun queryAndExportMmsParts(
        context: Context,
        mmsId: Long,
        sink: ArchiveSink
    ): List<MmsPartRecord> {
        val parts = mutableListOf<MmsPartRecord>()
        runCatching {
            val cr = context.contentResolver
            cr.query(
                MMS_PART_URI,
                arrayOf(
                    Telephony.Mms.Part._ID,
                    Telephony.Mms.Part.CONTENT_TYPE,
                    Telephony.Mms.Part.TEXT,
                    Telephony.Mms.Part._DATA,
                    Telephony.Mms.Part.NAME
                ),
                "${Telephony.Mms.Part.MSG_ID} = ?",
                arrayOf(mmsId.toString()),
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(Telephony.Mms.Part._ID)
                val ctIdx = c.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                val txtIdx = c.getColumnIndex(Telephony.Mms.Part.TEXT)
                val dataIdx = c.getColumnIndex(Telephony.Mms.Part._DATA)
                val nameIdx = c.getColumnIndex(Telephony.Mms.Part.NAME)

                while (c.moveToNext()) {
                    val partId = c.getLong(idIdx)
                    val ct = c.getString(ctIdx).orEmpty()
                    val text = if (txtIdx >= 0) c.getString(txtIdx) else null
                    val dataPath = if (dataIdx >= 0) c.getString(dataIdx) else null
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null

                    if (ct == "application/smil") continue

                    if (ct.startsWith("text/") || dataPath == null) {
                        parts += MmsPartRecord(contentType = ct, text = text, fileName = name)
                    } else {
                        val safeName = (name ?: "part_$partId").replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val archivePath = "messages/mms_media/${mmsId}_${partId}_$safeName"
                        val partUri = Uri.withAppendedPath(MMS_PART_URI, partId.toString())
                        val copied = runCatching {
                            cr.openInputStream(partUri)?.use { input ->
                                sink.copyStream(
                                    archivePath,
                                    ExportCategory.MESSAGES_SMS_MMS,
                                    input,
                                    compress = false
                                )
                                true
                            } ?: false
                        }.getOrDefault(false)
                        parts += MmsPartRecord(
                            contentType = ct,
                            text = text,
                            attachmentArchivePath = if (copied) archivePath else null,
                            fileName = safeName
                        )
                    }
                }
            }
        }
        return parts
    }

    private fun serializeSmsBackupXml(smsList: List<SmsRecord>): String {
        val writer = StringWriter()
        val xml = Xml.newSerializer()
        xml.setOutput(writer)
        xml.startDocument("UTF-8", true)
        xml.startTag("", "smses")
        xml.attribute("", "count", smsList.size.toString())
        for (sms in smsList) {
            xml.startTag("", "sms")
            xml.attribute("", "protocol", "0")
            xml.attribute("", "address", sms.address)
            xml.attribute("", "date", sms.dateMs.toString())
            xml.attribute("", "type", sms.type.toString())
            xml.attribute("", "subject", sms.subject ?: "null")
            xml.attribute("", "body", sms.body)
            xml.attribute("", "read", sms.read.toString())
            xml.attribute("", "date_sent", sms.dateSentMs.toString())
            xml.endTag("", "sms")
        }
        xml.endTag("", "smses")
        xml.endDocument()
        return writer.toString()
    }

    fun exportCallLog(
        context: Context,
        sink: ArchiveSink,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        if (!PermissionHelper.hasCallLogRead(context)) return 0
        val records = mutableListOf<CallLogRecord>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val total = c.count
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)

                var count = 0
                while (c.moveToNext()) {
                    count++
                    val num = c.getString(numIdx).orEmpty()
                    if (count % 25 == 0 || count == total) {
                        onItemProgress(count, total, num.ifBlank { "Call #$count" })
                    }
                    records += CallLogRecord(
                        number = num,
                        cachedName = c.getString(nameIdx),
                        dateMs = c.getLong(dateIdx),
                        durationSec = c.getLong(durIdx),
                        type = c.getInt(typeIdx)
                    )
                }
            }
        }
        val bytes = EtgJson.encodeToString(records).toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("calls/call_log.json", ExportCategory.CALL_LOG, bytes, compress = true)
        return records.size
    }

    fun importSmsMms(
        context: Context,
        jsonBytes: ByteArray?,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Pair<CategoryImportStat, Boolean> {
        if (jsonBytes == null) {
            return CategoryImportStat(ExportCategory.MESSAGES_SMS_MMS, notes = "Missing sms_mms.json") to false
        }
        val backup = runCatching {
            EtgJson.decodeFromString<SmsMmsBackup>(String(jsonBytes, StandardCharsets.UTF_8))
        }.getOrElse {
            return CategoryImportStat(ExportCategory.MESSAGES_SMS_MMS, notes = "Invalid SMS JSON") to false
        }

        if (!PermissionHelper.isDefaultSmsApp(context)) {
            return CategoryImportStat(
                category = ExportCategory.MESSAGES_SMS_MMS,
                notes = "Temporary default SMS role required to insert ${backup.sms.size} SMS messages"
            ) to true
        }

        val cr = context.contentResolver
        val existing = hashSetOf<String>()
        runCatching {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null,
                null,
                null
            )?.use { c ->
                val aIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val dIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val tIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                while (c.moveToNext()) {
                    existing += "${c.getString(aIdx).orEmpty()}|${c.getLong(dIdx)}|${c.getInt(tIdx)}"
                }
            }
        }

        var imported = 0
        var skipped = 0
        var failed = 0
        val total = backup.sms.size

        for ((index, sms) in backup.sms.withIndex()) {
            if ((index + 1) % 20 == 0 || index + 1 == total) {
                onItemProgress(index + 1, total, "SMS: ${sms.address}")
            }
            val key = "${sms.address}|${sms.dateMs}|${sms.type}"
            if (existing.contains(key)) {
                skipped++
                continue
            }
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sms.address)
                put(Telephony.Sms.BODY, sms.body)
                put(Telephony.Sms.DATE, sms.dateMs)
                if (sms.dateSentMs > 0L) put(Telephony.Sms.DATE_SENT, sms.dateSentMs)
                put(Telephony.Sms.TYPE, sms.type)
                put(Telephony.Sms.READ, sms.read)
                put(Telephony.Sms.SEEN, sms.seen)
                if (!sms.subject.isNullOrBlank()) put(Telephony.Sms.SUBJECT, sms.subject)
                if (sms.address.isNotBlank()) {
                    runCatching {
                        val tid = Telephony.Threads.getOrCreateThreadId(context, sms.address)
                        if (tid > 0L) put(Telephony.Sms.THREAD_ID, tid)
                    }
                }
            }
            runCatching {
                val uri = cr.insert(Telephony.Sms.CONTENT_URI, values)
                if (uri != null) {
                    imported++
                    existing += key
                } else {
                    failed++
                }
            }.onFailure {
                failed++
            }
        }

        return CategoryImportStat(
            category = ExportCategory.MESSAGES_SMS_MMS,
            importedCount = imported,
            skippedDuplicates = skipped,
            failedCount = failed,
            notes = if (backup.mms.isNotEmpty()) {
                "SMS restored; ${backup.mms.size} MMS records archived with attachments under messages/mms_media/"
            } else ""
        ) to false
    }

    fun importCallLog(
        context: Context,
        jsonBytes: ByteArray?,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): CategoryImportStat {
        if (!PermissionHelper.hasCallLogWrite(context)) {
            return CategoryImportStat(
                category = ExportCategory.CALL_LOG,
                notes = "Missing WRITE_CALL_LOG permission"
            )
        }
        if (jsonBytes == null) {
            return CategoryImportStat(ExportCategory.CALL_LOG, notes = "Missing call_log.json")
        }
        val calls = runCatching {
            EtgJson.decodeFromString<List<CallLogRecord>>(String(jsonBytes, StandardCharsets.UTF_8))
        }.getOrElse { emptyList() }
        if (calls.isEmpty()) return CategoryImportStat(ExportCategory.CALL_LOG)

        val cr = context.contentResolver
        val existing = hashSetOf<String>()
        if (PermissionHelper.hasCallLogRead(context)) {
            runCatching {
                cr.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                    null,
                    null,
                    null
                )?.use { c ->
                    val nIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                    val dIdx = c.getColumnIndex(CallLog.Calls.DATE)
                    while (c.moveToNext()) {
                        existing += "${c.getString(nIdx).orEmpty()}|${c.getLong(dIdx)}"
                    }
                }
            }
        }

        var imported = 0
        var skipped = 0
        var failed = 0
        val total = calls.size
        for ((index, call) in calls.withIndex()) {
            if ((index + 1) % 25 == 0 || index + 1 == total) {
                onItemProgress(index + 1, total, call.number)
            }
            val key = "${call.number}|${call.dateMs}"
            if (existing.contains(key)) {
                skipped++
                continue
            }
            val cv = ContentValues().apply {
                put(CallLog.Calls.NUMBER, call.number)
                if (!call.cachedName.isNullOrBlank()) {
                    put(CallLog.Calls.CACHED_NAME, call.cachedName)
                }
                put(CallLog.Calls.DATE, call.dateMs)
                put(CallLog.Calls.DURATION, call.durationSec)
                put(CallLog.Calls.TYPE, call.type)
                put(CallLog.Calls.NEW, 0)
            }
            runCatching {
                if (cr.insert(CallLog.Calls.CONTENT_URI, cv) != null) {
                    imported++
                    existing += key
                } else {
                    failed++
                }
            }.onFailure { failed++ }
        }
        return CategoryImportStat(
            category = ExportCategory.CALL_LOG,
            importedCount = imported,
            skippedDuplicates = skipped,
            failedCount = failed
        )
    }
}
