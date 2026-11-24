package org.etg.migration.data.integration

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

object SinusChatBridge {
    const val PACKAGE = "pl.sinusoide.sinuschat"
    private const val ACTION_EXPORT = "pl.sinusoide.sinuschat.action.EXPORT_BACKUP"
    private const val EXTRA_WITH_MEDIA = "with_media"
    private const val EXTRA_KEY_ID = "backup_key_id"
    private const val EXTRA_ERROR = "error"
    private const val EXTENSION = ".sinuschat"

    fun isBackup(fileName: String) = fileName.endsWith(EXTENSION, ignoreCase = true)

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun exportIntent(withMedia: Boolean = true): Intent =
        Intent(ACTION_EXPORT).setPackage(PACKAGE).putExtra(EXTRA_WITH_MEDIA, withMedia)

    fun supportsExport(context: Context): Boolean =
        runCatching { exportIntent().resolveActivity(context.packageManager) != null }.getOrDefault(false)

    sealed interface ExportOutcome {
        data class Ready(val file: File, val keyId: String, val withMedia: Boolean) : ExportOutcome

        data class Failed(val code: String) : ExportOutcome
    }

    fun receiveExport(
        context: Context,
        resultCode: Int,
        data: Intent?,
        onProgress: (Float) -> Unit = {}
    ): ExportOutcome {
        if (resultCode != Activity.RESULT_OK) {
            return ExportOutcome.Failed(data?.getStringExtra(EXTRA_ERROR) ?: "cancelled")
        }
        val uri = data?.data ?: return ExportOutcome.Failed("failed")
        val name = safeName(data.getStringExtra(Intent.EXTRA_TITLE) ?: displayName(context, uri))
        val dir = exportDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        val target = File(dir, name)
        val total = sizeOf(context, uri)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            } ?: error("no stream")
            target.length() > 0
        }.getOrDefault(false)
        if (!copied) {
            target.delete()
            return ExportOutcome.Failed("copy_failed")
        }
        return ExportOutcome.Ready(
            file = target,
            keyId = data.getStringExtra(EXTRA_KEY_ID).orEmpty(),
            withMedia = data.getBooleanExtra(EXTRA_WITH_MEDIA, true)
        )
    }

    fun isReceivedExport(context: Context, uri: String): Boolean =
        uri.startsWith(Uri.fromFile(exportDir(context)).toString())

    fun clearHandoff(context: Context) {
        handoffDir(context).deleteRecursively()
    }

    fun handoffFile(context: Context, fileName: String): File =
        File(handoffDir(context).apply { mkdirs() }, safeName(fileName))

    fun restoredBackups(context: Context): List<File> =
        handoffDir(context).listFiles { f -> f.isFile && isBackup(f.name) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun restoreIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/octet-stream")
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun exportDir(context: Context) = File(context.filesDir, "etg_sinuschat_export")
    private fun handoffDir(context: Context) = File(context.filesDir, "etg_handoff/sinuschat")

    private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun safeName(raw: String?): String {
        val base = raw?.substringAfterLast('/')?.replace(Regex("[\\\\:*?\"<>|\\x00-\\x1f]"), "_")?.trim()
            ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "SinusChat-backup"
        return if (isBackup(base)) base else "$base$EXTENSION"
    }
}
