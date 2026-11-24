package org.etg.migration.data.archive

import org.etg.migration.model.ExportCategory
import org.etg.migration.model.IssuePhase
import org.etg.migration.model.IssueReason
import org.etg.migration.model.TransferIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream

class IssueHandlingTest {
    @Test
    fun failedSourceIsMarkedAndRetryGetsAlias() {
        val bytes = ByteArrayOutputStream()
        val sink = ZipArchiveSink(bytes, null)
        sink.writeBytes("a.txt", ExportCategory.DOCUMENTS_DOWNLOADS, "ok".toByteArray())
        try {
            sink.writeEntry("b.txt", ExportCategory.DOCUMENTS_DOWNLOADS) { out ->
                out.write("partial".toByteArray())
                throw FileNotFoundException("gone")
            }
            fail("expected the source error to reach the caller")
        } catch (_: FileNotFoundException) {
        }
        assertEquals(listOf("b.txt"), sink.failedEntries)
        assertFalse(sink.hasEntry("b.txt"))

        sink.writeBytes("b.txt", ExportCategory.DOCUMENTS_DOWNLOADS, "full".toByteArray())
        assertTrue(sink.hasEntry("b.txt"))
        assertEquals(mapOf("b.txt.etgretry1" to "b.txt"), sink.entryAliases)

        sink.writeBytes("a.txt", ExportCategory.DOCUMENTS_DOWNLOADS, "dup".toByteArray())
        sink.close()

        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(listOf("a.txt", "b.txt", "b.txt.etgretry1"), names)
    }

    @Test
    fun brokenDestinationStopsTheExport() {
        val dead = object : OutputStream() {
            override fun write(b: Int) = throw IOException("cable unplugged")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("cable unplugged")
        }
        val sink = ZipArchiveSink(dead, null)
        try {
            sink.writeBytes("big.bin", ExportCategory.PHOTOS_VIDEOS, ByteArray(300_000))
            sink.writeBytes("big2.bin", ExportCategory.PHOTOS_VIDEOS, ByteArray(300_000))
            fail("expected SinkBrokenException")
        } catch (e: SinkBrokenException) {
            assertTrue(IssueLog.isFatal(e))
        }
    }

    @Test
    fun attemptDoesNotSwallowFatalErrors() {
        val r = attempt { throw FileNotFoundException("x") }
        assertTrue(r.isFailure)
        try {
            attempt { throw SinkBrokenException(IOException("dead")) }
            fail("fatal error must propagate")
        } catch (_: SinkBrokenException) {
        }
    }

    @Test
    fun retryRemovesFixedIssueAndKeepsFailingOne() {
        val log = IssueLog()
        var tries = 0
        val fixable = TransferIssue(phase = IssuePhase.EXPORT, category = ExportCategory.PHOTOS_VIDEOS, itemName = "a.jpg", reason = IssueReason.IO_ERROR)
        val stubborn = TransferIssue(phase = IssuePhase.EXPORT, category = ExportCategory.PHOTOS_VIDEOS, itemName = "b.jpg", reason = IssueReason.IO_ERROR)
        log.report(fixable) { if (++tries >= 1) null else IssueReason.IO_ERROR to "" }
        log.report(stubborn) { IssueReason.NOT_FOUND to "still missing" }
        log.report(stubborn.copy(id = "no-retry"))

        assertNull(log.retry(fixable.id))
        val again = log.retry(stubborn.id)!!
        assertEquals(IssueReason.NOT_FOUND, again.reason)
        assertEquals(1, again.attempts)
        assertFalse(log.all().first { it.id == "no-retry" }.retryable)
        assertEquals(2, log.all().size)
    }

    @Test
    fun reasonsAreMappedFromExceptions() {
        assertEquals(IssueReason.NOT_FOUND, IssueLog.reasonOf(FileNotFoundException()).first)
        assertEquals(IssueReason.NO_PERMISSION, IssueLog.reasonOf(SecurityException()).first)
        assertEquals(IssueReason.IO_ERROR, IssueLog.reasonOf(IOException()).first)
    }
}
