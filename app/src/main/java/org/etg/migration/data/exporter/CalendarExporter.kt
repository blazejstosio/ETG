package org.etg.migration.data.exporter

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import kotlinx.serialization.encodeToString
import org.etg.migration.codec.ICalendarCodec
import org.etg.migration.data.PermissionHelper
import org.etg.migration.data.archive.ArchiveSink
import org.etg.migration.data.archive.EtgJson
import org.etg.migration.model.CalendarEventRecord
import org.etg.migration.model.CalendarRecord
import org.etg.migration.model.CalendarReminderRecord
import org.etg.migration.model.CategoryImportStat
import org.etg.migration.model.CategoryScanInfo
import org.etg.migration.model.CategoryStatus
import org.etg.migration.model.ExportCategory
import java.nio.charset.StandardCharsets

object CalendarExporter {
    fun listEvents(context: Context): List<SelectableItem> {
        if (!PermissionHelper.hasCalendarRead(context)) return emptyList()
        val calNames = hashMapOf<Long, String>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                null, null, null
            )?.use { c -> while (c.moveToNext()) calNames[c.getLong(0)] = c.getString(1).orEmpty() }
        }
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        val out = mutableListOf<SelectableItem>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.CALENDAR_ID,
                    CalendarContract.Events.RRULE
                ),
                "${CalendarContract.Events.DELETED} = 0",
                null,
                "${CalendarContract.Events.DTSTART} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val repeat = if (!c.getString(4).isNullOrBlank()) " ↻" else ""
                    out += SelectableItem(
                        key = c.getLong(0).toString(),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "—",
                        subtitle = fmt.format(java.util.Date(c.getLong(2))) + repeat + " · " + calNames[c.getLong(3)].orEmpty()
                    )
                }
            }
        }
        return out
    }

    private const val LOCAL_ACCOUNT_NAME = "ETG Local Calendar"

    fun scan(context: Context): CategoryScanInfo {
        if (!PermissionHelper.hasCalendarRead(context)) {
            return CategoryScanInfo(
                category = ExportCategory.CALENDAR,
                status = CategoryStatus.NEEDS_PERMISSION,
                missingPermissions = listOf(Manifest.permission.READ_CALENDAR)
            )
        }
        var count = 0
        runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.DELETED} = 0",
                null,
                null
            )?.use { count = it.count }
        }
        return CategoryScanInfo(
            category = ExportCategory.CALENDAR,
            itemCount = count,
            estimatedBytes = count * 1_500L,
            status = if (count > 0) CategoryStatus.READY else CategoryStatus.EMPTY,
            detailSummary = "$count"
        )
    }

    fun export(
        context: Context,
        sink: ArchiveSink,
        excludedEventIds: Set<String> = emptySet(),
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): Int {
        if (!PermissionHelper.hasCalendarRead(context)) return 0
        val cr = context.contentResolver

        val calendarsById = linkedMapOf<Long, MutableList<CalendarEventRecord>>()
        val calendarMeta = linkedMapOf<Long, CalendarRecord>()

        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.NAME,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.CALENDAR_TIME_ZONE
            ),
            null,
            null,
            null
        )?.use { c ->
            val idIdx = c.getColumnIndex(CalendarContract.Calendars._ID)
            val nameIdx = c.getColumnIndex(CalendarContract.Calendars.NAME)
            val dispIdx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accNameIdx = c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val accTypeIdx = c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
            val colorIdx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_COLOR)
            val tzIdx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_TIME_ZONE)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx).orEmpty()
                val displayName = c.getString(dispIdx).orEmpty().ifBlank { name.ifBlank { "Calendar $id" } }
                calendarMeta[id] = CalendarRecord(
                    id = id,
                    name = name,
                    displayName = displayName,
                    accountName = c.getString(accNameIdx).orEmpty(),
                    accountType = c.getString(accTypeIdx).orEmpty(),
                    color = c.getInt(colorIdx),
                    timeZone = c.getString(tzIdx).orEmpty().ifBlank { "UTC" }
                )
                calendarsById[id] = mutableListOf()
            }
        }

        val remindersByEvent = mutableMapOf<Long, MutableList<CalendarReminderRecord>>()
        runCatching {
            cr.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(
                    CalendarContract.Reminders.EVENT_ID,
                    CalendarContract.Reminders.MINUTES,
                    CalendarContract.Reminders.METHOD
                ),
                null,
                null,
                null
            )?.use { c ->
                val evIdx = c.getColumnIndex(CalendarContract.Reminders.EVENT_ID)
                val minIdx = c.getColumnIndex(CalendarContract.Reminders.MINUTES)
                val methIdx = c.getColumnIndex(CalendarContract.Reminders.METHOD)
                while (c.moveToNext()) {
                    val evId = c.getLong(evIdx)
                    remindersByEvent.getOrPut(evId) { mutableListOf() } += CalendarReminderRecord(
                        minutes = c.getInt(minIdx),
                        method = c.getInt(methIdx)
                    )
                }
            }
        }

        var exportedEvents = 0
        cr.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.DURATION,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.RDATE,
                CalendarContract.Events.EXDATE,
                CalendarContract.Events._SYNC_ID
            ),
            "${CalendarContract.Events.DELETED} = 0",
            null,
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { c ->
            val total = c.count
            val idIdx = c.getColumnIndex(CalendarContract.Events._ID)
            val calIdx = c.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
            val titleIdx = c.getColumnIndex(CalendarContract.Events.TITLE)
            val descIdx = c.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val locIdx = c.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val startIdx = c.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIdx = c.getColumnIndex(CalendarContract.Events.DTEND)
            val durIdx = c.getColumnIndex(CalendarContract.Events.DURATION)
            val allDayIdx = c.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val tzIdx = c.getColumnIndex(CalendarContract.Events.EVENT_TIMEZONE)
            val rruleIdx = c.getColumnIndex(CalendarContract.Events.RRULE)
            val rdateIdx = c.getColumnIndex(CalendarContract.Events.RDATE)
            val exdateIdx = c.getColumnIndex(CalendarContract.Events.EXDATE)
            val syncIdx = c.getColumnIndex(CalendarContract.Events._SYNC_ID)

            while (c.moveToNext()) {
                val evId = c.getLong(idIdx)
                if (evId.toString() in excludedEventIds) continue
                val calId = c.getLong(calIdx)
                val title = c.getString(titleIdx).orEmpty()
                val startMs = c.getLong(startIdx)
                val endMs = c.getLong(endIdx)
                val uid = c.getString(syncIdx)?.takeIf { it.isNotBlank() } ?: "etg-$calId-$evId-$startMs"
                val calName = calendarMeta[calId]?.displayName ?: "Calendar $calId"

                exportedEvents++
                onItemProgress(exportedEvents, total, title.ifBlank { "Event #$evId" })

                val record = CalendarEventRecord(
                    uid = uid,
                    calendarName = calName,
                    title = title,
                    description = c.getString(descIdx).orEmpty(),
                    location = c.getString(locIdx).orEmpty(),
                    dtStartMs = startMs,
                    dtEndMs = endMs,
                    allDay = c.getInt(allDayIdx) == 1,
                    timezone = c.getString(tzIdx).orEmpty().ifBlank { "UTC" },
                    rrule = c.getString(rruleIdx),
                    rdate = c.getString(rdateIdx),
                    exdate = c.getString(exdateIdx),
                    duration = c.getString(durIdx),
                    reminders = remindersByEvent[evId].orEmpty()
                )
                calendarsById.getOrPut(calId) { mutableListOf() } += record
            }
        }

        val fullCalendars = calendarMeta.values.map { meta ->
            meta.copy(events = calendarsById[meta.id].orEmpty())
        }.filter { it.events.isNotEmpty() }

        val jsonBytes = EtgJson.encodeToString(fullCalendars).toByteArray(StandardCharsets.UTF_8)
        sink.writeBytes("calendar/calendar.json", ExportCategory.CALENDAR, jsonBytes, compress = true)

        val usedNames = hashSetOf<String>()
        for (cal in fullCalendars) {
            var safe = cal.displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_').ifBlank { "calendar_${cal.id}" }
            while (!usedNames.add(safe)) {
                safe = "${safe}_${cal.id}"
            }
            val ics = ICalendarCodec.encodeCalendar(cal.displayName, cal.events)
            sink.writeBytes(
                "calendar/$safe.ics",
                ExportCategory.CALENDAR,
                ics.toByteArray(StandardCharsets.UTF_8),
                compress = true
            )
        }
        return exportedEvents
    }

    fun import(
        context: Context,
        jsonBytes: ByteArray?,
        onItemProgress: (processed: Int, total: Int, label: String) -> Unit
    ): CategoryImportStat {
        if (!PermissionHelper.hasCalendarWrite(context) || !PermissionHelper.hasCalendarRead(context)) {
            return CategoryImportStat(
                category = ExportCategory.CALENDAR,
                notes = "Missing READ_CALENDAR / WRITE_CALENDAR permission"
            )
        }
        if (jsonBytes == null) {
            return CategoryImportStat(ExportCategory.CALENDAR, notes = "Missing calendar.json")
        }
        val calendars = runCatching {
            EtgJson.decodeFromString<List<CalendarRecord>>(String(jsonBytes, StandardCharsets.UTF_8))
        }.getOrElse { emptyList() }

        val allEvents = calendars.flatMap { it.events }
        if (allEvents.isEmpty()) {
            return CategoryImportStat(ExportCategory.CALENDAR, notes = "No events in archive")
        }

        val targetCalendarId = findOrCreateLocalCalendar(context)
            ?: return CategoryImportStat(
                category = ExportCategory.CALENDAR,
                failedCount = allEvents.size,
                notes = "Failed to find or create local writable calendar"
            )

        val cr = context.contentResolver
        val existingKeys = hashSetOf<String>()
        runCatching {
            cr.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(targetCalendarId.toString()),
                null
            )?.use { c ->
                val tIdx = c.getColumnIndex(CalendarContract.Events.TITLE)
                val sIdx = c.getColumnIndex(CalendarContract.Events.DTSTART)
                while (c.moveToNext()) {
                    existingKeys += "${c.getString(tIdx).orEmpty()}|${c.getLong(sIdx)}"
                }
            }
        }

        var imported = 0
        var skipped = 0
        var failed = 0
        val total = allEvents.size

        for ((index, ev) in allEvents.withIndex()) {
            onItemProgress(index + 1, total, ev.title.ifBlank { "Event #${index + 1}" })
            val key = "${ev.title}|${ev.dtStartMs}"
            if (existingKeys.contains(key)) {
                skipped++
                continue
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, targetCalendarId)
                put(CalendarContract.Events.TITLE, ev.title)
                put(CalendarContract.Events.DESCRIPTION, ev.description)
                put(CalendarContract.Events.EVENT_LOCATION, ev.location)
                put(CalendarContract.Events.DTSTART, ev.dtStartMs)
                if (!ev.rrule.isNullOrBlank()) {
                    put(CalendarContract.Events.RRULE, ev.rrule)
                    val dur = ev.duration?.takeIf { it.isNotBlank() } ?: run {
                        val secs = maxOf(60L, (ev.dtEndMs - ev.dtStartMs) / 1000L)
                        "PT${secs}S"
                    }
                    put(CalendarContract.Events.DURATION, dur)
                } else {
                    val safeEnd = if (ev.dtEndMs >= ev.dtStartMs) ev.dtEndMs else ev.dtStartMs + 3_600_000L
                    put(CalendarContract.Events.DTEND, safeEnd)
                }
                put(CalendarContract.Events.ALL_DAY, if (ev.allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, ev.timezone.ifBlank { "UTC" })
            }

            runCatching {
                val insertedUri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
                val eventId = insertedUri?.let { ContentUris.parseId(it) } ?: -1L
                if (eventId > 0L) {
                    for (rem in ev.reminders) {
                        val remValues = ContentValues().apply {
                            put(CalendarContract.Reminders.EVENT_ID, eventId)
                            put(CalendarContract.Reminders.MINUTES, maxOf(0, rem.minutes))
                            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        }
                        runCatching { cr.insert(CalendarContract.Reminders.CONTENT_URI, remValues) }
                    }
                    existingKeys += key
                    imported++
                } else {
                    failed++
                }
            }.onFailure {
                failed++
            }
        }

        return CategoryImportStat(
            category = ExportCategory.CALENDAR,
            importedCount = imported,
            skippedDuplicates = skipped,
            failedCount = failed
        )
    }

    private fun findOrCreateLocalCalendar(context: Context): Long? {
        val cr = context.contentResolver
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.ACCOUNT_TYPE
            ),
            null,
            null,
            null
        )?.use { c ->
            val idIdx = c.getColumnIndex(CalendarContract.Calendars._ID)
            val accessIdx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val accTypeIdx = c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
            var fallbackWritable: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val access = c.getInt(accessIdx)
                val accType = c.getString(accTypeIdx)
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    if (accType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
                        return id
                    }
                    if (fallbackWritable == null) fallbackWritable = id
                }
            }
            if (fallbackWritable != null) return fallbackWritable
        }

        val syncUri = asSyncAdapter(
            CalendarContract.Calendars.CONTENT_URI,
            LOCAL_ACCOUNT_NAME,
            CalendarContract.ACCOUNT_TYPE_LOCAL
        )
        val cv = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF5EA8FF.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
        }
        return runCatching {
            cr.insert(syncUri, cv)?.let { ContentUris.parseId(it) }
        }.getOrNull()
    }

    private fun asSyncAdapter(uri: Uri, account: String, accountType: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            .build()
}
