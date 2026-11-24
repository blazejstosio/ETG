package org.etg.migration.data.archive

import org.etg.migration.model.IssueReason
import org.etg.migration.model.TransferIssue
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipException

class SinkBrokenException(cause: Throwable) : IOException(cause.message ?: "Destination stopped responding", cause)

class IssueLog {
    private val issues = mutableListOf<TransferIssue>()
    private val retries = mutableMapOf<String, () -> Pair<IssueReason, String>?>()

    @Synchronized
    fun report(issue: TransferIssue, retry: (() -> Pair<IssueReason, String>?)? = null) {
        val withFlag = issue.copy(retryable = issue.retryable && retry != null)
        issues += withFlag
        if (retry != null) retries[issue.id] = retry
    }

    @Synchronized
    fun all(): List<TransferIssue> = issues.toList()

    val isEmpty: Boolean @Synchronized get() = issues.isEmpty()

    fun retry(id: String): TransferIssue? {
        val (issue, action) = synchronized(this) {
            (issues.firstOrNull { it.id == id } ?: return null) to retries[id]
        }
        if (action == null) return issue
        val result = runCatching { action() }.getOrElse { t ->
            if (isFatal(t)) throw t
            reasonOf(t)
        }
        return synchronized(this) {
            if (result == null) {
                issues.removeAll { it.id == id }
                retries.remove(id)
                null
            } else {
                val updated = issue.copy(reason = result.first, reasonDetail = result.second, attempts = issue.attempts + 1)
                val idx = issues.indexOfFirst { it.id == id }
                if (idx >= 0) issues[idx] = updated
                updated
            }
        }
    }

    companion object {
        fun isFatal(t: Throwable): Boolean =
            t is CancellationException || t is kotlinx.coroutines.CancellationException ||
                t is SinkBrokenException || t is InterruptedException || t is OutOfMemoryError

        fun reasonOf(t: Throwable): Pair<IssueReason, String> {
            val msg = (t.message ?: t.javaClass.simpleName).take(160)
            val code = when (t) {
                is FileNotFoundException -> IssueReason.NOT_FOUND
                is SecurityException -> IssueReason.NO_PERMISSION
                is ZipException -> IssueReason.CORRUPTED
                is IOException -> IssueReason.IO_ERROR
                else -> IssueReason.UNKNOWN
            }
            return code to msg
        }
    }
}

inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (t: Throwable) {
    if (IssueLog.isFatal(t)) throw t
    Result.failure(t)
}
