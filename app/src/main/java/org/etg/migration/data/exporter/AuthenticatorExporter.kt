package org.etg.migration.data.exporter

import org.etg.migration.codec.OtpMigrationCodec
import org.etg.migration.data.archive.ArchiveSink
import org.etg.migration.model.ExportCategory
import org.etg.migration.model.MediaFileEntry
import org.etg.migration.model.OtpAccount
import java.nio.charset.StandardCharsets

object AuthenticatorExporter {
    private const val ARCHIVE_DIR = "vault/authenticator"
    private const val RESTORE_DIR = "Documents/ETG_2FA"

    fun export(
        accounts: List<OtpAccount>,
        sink: ArchiveSink,
        mediaIndexOut: MutableList<MediaFileEntry>
    ): Int {
        if (accounts.isEmpty()) return 0
        val nowSec = System.currentTimeMillis() / 1000L
        val files = listOf(
            Triple("aegis_import.json", "application/json", OtpMigrationCodec.toAegisPlainJson(accounts)),
            Triple("otpauth_uris.txt", "text/plain", OtpMigrationCodec.toOtpAuthList(accounts))
        )
        for ((name, mime, content) in files) {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            val path = "$ARCHIVE_DIR/$name"
            sink.writeBytes(path, ExportCategory.VAULT_KEYS_2FA, bytes, compress = true)
            mediaIndexOut += MediaFileEntry(
                category = ExportCategory.VAULT_KEYS_2FA,
                archivePath = path,
                relativePath = RESTORE_DIR,
                fileName = name,
                mimeType = mime,
                sizeBytes = bytes.size.toLong(),
                dateModifiedSec = nowSec
            )
        }
        return accounts.size
    }
}
