package org.etg.migration.codec

import org.etg.migration.model.CalendarEventRecord
import org.etg.migration.model.CalendarReminderRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ICalendarCodecTest {
    @Test
    fun roundTripEventsRecurrenceAndAlarms() {
        val timedStart = LocalDate.of(2026, 11, 25).atTime(14, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val timedEnd = timedStart + 3_600_000L
        val allDayStart = LocalDate.of(2026, 12, 24).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val allDayEnd = allDayStart + 86_400_000L

        val events = listOf(
            CalendarEventRecord(
                uid = "uid-timed-1",
                calendarName = "Mój Kalendarz",
                title = "Spotkanie w Krakowie, ul. Grodzka",
                description = "Agenda:\n1. Migracja do GrapheneOS",
                location = "Kraków, Polska",
                dtStartMs = timedStart,
                dtEndMs = timedEnd,
                allDay = false,
                rrule = "FREQ=WEEKLY;COUNT=4",
                reminders = listOf(CalendarReminderRecord(minutes = 30))
            ),
            CalendarEventRecord(
                uid = "uid-allday-2",
                calendarName = "Mój Kalendarz",
                title = "Wigilia",
                dtStartMs = allDayStart,
                dtEndMs = allDayEnd,
                allDay = true
            )
        )

        val ics = ICalendarCodec.encodeCalendar("Mój Kalendarz", events)
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;COUNT=4"))

        val decoded = ICalendarCodec.decodeCalendar(ics)
        assertEquals(2, decoded.size)

        val first = decoded[0]
        assertEquals("uid-timed-1", first.uid)
        assertEquals("Mój Kalendarz", first.calendarName)
        assertEquals("Spotkanie w Krakowie, ul. Grodzka", first.title)
        assertEquals("Agenda:\n1. Migracja do GrapheneOS", first.description)
        assertEquals("Kraków, Polska", first.location)
        assertEquals(timedStart, first.dtStartMs)
        assertEquals(timedEnd, first.dtEndMs)
        assertEquals(false, first.allDay)
        assertEquals("FREQ=WEEKLY;COUNT=4", first.rrule)
        assertEquals(1, first.reminders.size)
        assertEquals(30, first.reminders[0].minutes)

        val second = decoded[1]
        assertEquals("uid-allday-2", second.uid)
        assertEquals(true, second.allDay)
        assertEquals(allDayStart, second.dtStartMs)
    }
}
