package org.etg.migration.codec

import org.etg.migration.model.CalendarEventRecord
import org.etg.migration.model.CalendarReminderRecord
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object ICalendarCodec {
    private val utcFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val localDateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun encodeCalendar(calendarName: String, events: List<CalendarEventRecord>): String {
        val lines = mutableListOf<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//ETG//Export To Graphene//EN"
        lines += "CALSCALE:GREGORIAN"
        lines += "X-WR-CALNAME:${escapeText(calendarName)}"

        for (event in events) {
            lines += "BEGIN:VEVENT"
            lines += "UID:${escapeText(event.uid)}"
            lines += "DTSTAMP:${utcFormatter.format(Instant.now())}"
            if (event.allDay) {
                val startDate = Instant.ofEpochMilli(event.dtStartMs)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                val endDate = if (event.dtEndMs > event.dtStartMs) {
                    Instant.ofEpochMilli(event.dtEndMs).atZone(ZoneOffset.UTC).toLocalDate()
                } else {
                    startDate.plusDays(1)
                }
                lines += "DTSTART;VALUE=DATE:${dateFormatter.format(startDate)}"
                lines += "DTEND;VALUE=DATE:${dateFormatter.format(endDate)}"
            } else {
                lines += "DTSTART:${utcFormatter.format(Instant.ofEpochMilli(event.dtStartMs))}"
                if (event.dtEndMs > 0L) {
                    lines += "DTEND:${utcFormatter.format(Instant.ofEpochMilli(event.dtEndMs))}"
                } else if (!event.duration.isNullOrBlank()) {
                    lines += "DURATION:${event.duration}"
                }
            }
            lines += "SUMMARY:${escapeText(event.title)}"
            if (event.description.isNotBlank()) {
                lines += "DESCRIPTION:${escapeText(event.description)}"
            }
            if (event.location.isNotBlank()) {
                lines += "LOCATION:${escapeText(event.location)}"
            }
            if (!event.rrule.isNullOrBlank()) {
                lines += "RRULE:${event.rrule}"
            }
            if (!event.rdate.isNullOrBlank()) {
                lines += "RDATE:${event.rdate}"
            }
            if (!event.exdate.isNullOrBlank()) {
                lines += "EXDATE:${event.exdate}"
            }
            for (reminder in event.reminders) {
                lines += "BEGIN:VALARM"
                lines += "ACTION:DISPLAY"
                lines += "DESCRIPTION:Reminder"
                lines += "TRIGGER:-PT${maxOf(0, reminder.minutes)}M"
                lines += "END:VALARM"
            }
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"

        val out = StringBuilder()
        for (line in lines) {
            out.append(VCardCodec.foldLine(line)).append("\r\n")
        }
        return out.toString()
    }

    fun decodeCalendar(icsContent: String, defaultCalendarName: String = "ETG Local Calendar"): List<CalendarEventRecord> {
        val lines = VCardCodec.unfoldLines(icsContent)
        var calName = defaultCalendarName
        val events = mutableListOf<CalendarEventRecord>()

        var inEvent = false
        var inAlarm = false
        var uid = ""
        var summary = ""
        var description = ""
        var location = ""
        var dtStartMs = 0L
        var dtEndMs = 0L
        var allDay = false
        var rrule: String? = null
        var rdate: String? = null
        var exdate: String? = null
        var duration: String? = null
        val reminders = mutableListOf<CalendarReminderRecord>()

        for (rawLine in lines) {
            val line = rawLine.trimEnd('\r', '\n')
            if (line.isBlank()) continue
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    inEvent = true
                    inAlarm = false
                    uid = ""
                    summary = ""
                    description = ""
                    location = ""
                    dtStartMs = 0L
                    dtEndMs = 0L
                    allDay = false
                    rrule = null
                    rdate = null
                    exdate = null
                    duration = null
                    reminders.clear()
                    continue
                }
                line.equals("END:VEVENT", ignoreCase = true) -> {
                    if (inEvent && dtStartMs > 0L) {
                        val effectiveUid = uid.ifBlank { "${summary.hashCode()}-$dtStartMs" }
                        val effectiveEnd = if (dtEndMs > 0L) dtEndMs else {
                            if (allDay) dtStartMs + 86_400_000L else dtStartMs + 3_600_000L
                        }
                        events += CalendarEventRecord(
                            uid = effectiveUid,
                            calendarName = calName,
                            title = summary,
                            description = description,
                            location = location,
                            dtStartMs = dtStartMs,
                            dtEndMs = effectiveEnd,
                            allDay = allDay,
                            timezone = "UTC",
                            rrule = rrule,
                            rdate = rdate,
                            exdate = exdate,
                            duration = duration,
                            reminders = reminders.toList()
                        )
                    }
                    inEvent = false
                    inAlarm = false
                    continue
                }
                line.equals("BEGIN:VALARM", ignoreCase = true) -> {
                    if (inEvent) inAlarm = true
                    continue
                }
                line.equals("END:VALARM", ignoreCase = true) -> {
                    inAlarm = false
                    continue
                }
            }

            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val header = line.substring(0, colonIdx)
            val value = line.substring(colonIdx + 1)
            val propName = header.substringBefore(';').uppercase()
            val params = header.substringAfter(';', "").uppercase()

            if (!inEvent) {
                if (propName == "X-WR-CALNAME" && value.isNotBlank()) {
                    calName = unescapeText(value)
                }
                continue
            }

            if (inAlarm) {
                if (propName == "TRIGGER") {
                    parseTriggerMinutes(value)?.let { mins ->
                        reminders += CalendarReminderRecord(minutes = mins, method = 1)
                    }
                }
                continue
            }

            when (propName) {
                "UID" -> uid = unescapeText(value)
                "SUMMARY" -> summary = unescapeText(value)
                "DESCRIPTION" -> description = unescapeText(value)
                "LOCATION" -> location = unescapeText(value)
                "DTSTART" -> {
                    val parsed = parseDateOrDateTime(value, params.contains("VALUE=DATE"))
                    if (parsed != null) {
                        dtStartMs = parsed.first
                        allDay = parsed.second
                    }
                }
                "DTEND" -> {
                    val parsed = parseDateOrDateTime(value, params.contains("VALUE=DATE"))
                    if (parsed != null) {
                        dtEndMs = parsed.first
                    }
                }
                "RRULE" -> rrule = value.trim()
                "RDATE" -> rdate = value.trim()
                "EXDATE" -> exdate = value.trim()
                "DURATION" -> duration = value.trim()
            }
        }
        return events
    }

    private fun parseDateOrDateTime(value: String, explicitDate: Boolean): Pair<Long, Boolean>? {
        val clean = value.trim()
        if (clean.length == 8 || explicitDate) {
            return runCatching {
                val ld = LocalDate.parse(clean.take(8), dateFormatter)
                ld.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() to true
            }.getOrNull()
        }
        if (clean.endsWith("Z")) {
            return runCatching {
                val ldt = LocalDateTime.parse(clean.dropLast(1), localDateTimeFormatter)
                ldt.toInstant(ZoneOffset.UTC).toEpochMilli() to false
            }.getOrNull()
        }
        return runCatching {
            val ldt = LocalDateTime.parse(clean.take(15), localDateTimeFormatter)
            ldt.toInstant(ZoneOffset.UTC).toEpochMilli() to false
        }.getOrNull()
    }

    private fun parseTriggerMinutes(trigger: String): Int? {
        val t = trigger.trim().uppercase()
        val match = Regex("""-?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").matchEntire(t)
            ?: return null
        val days = match.groupValues[1].toIntOrNull() ?: 0
        val hours = match.groupValues[2].toIntOrNull() ?: 0
        val mins = match.groupValues[3].toIntOrNull() ?: 0
        val secs = match.groupValues[4].toIntOrNull() ?: 0
        return days * 1440 + hours * 60 + mins + (secs / 60)
    }

    private fun escapeText(text: String): String =
        text
            .replace("\\", "\\\\")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace(";", "\\;")
            .replace(",", "\\,")

    private fun unescapeText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n', 'N' -> {
                        sb.append('\n')
                        i += 2
                        continue
                    }
                    '\\', ';', ',' -> {
                        sb.append(next)
                        i += 2
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
