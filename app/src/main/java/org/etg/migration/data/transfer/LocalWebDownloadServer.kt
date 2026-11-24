package org.etg.migration.data.transfer

import org.etg.migration.crypto.Sha256Helper
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class LocalWebDownloadServer(
    private val archiveFile: File,
    private val downloadFileName: String,
    private val pin: String,
    val port: Int = DEFAULT_WEB_PORT
) {
    companion object {
        const val DEFAULT_WEB_PORT = 47822
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket(port).apply { reuseAddress = true }
        serverSocket = ss
        workerThread = Thread {
            while (running.get() && !ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                Thread { handleClient(client) }.start()
            }
        }.apply {
            name = "ETG-WebDownloadServer"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        workerThread = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 60_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val pathWithQuery = parts[1]

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val path = pathWithQuery.substringBefore('?')
            val query = pathWithQuery.substringAfter('?', "")
            val out = s.getOutputStream()

            when (path) {
                "/" -> sendHtml(out, buildLandingHtml(error = false))
                "/download" -> {
                    val providedPin = parseQueryParam(query, "pin").orEmpty()
                    val pinOk = Sha256Helper.constantTimeEquals(
                        providedPin.toByteArray(StandardCharsets.UTF_8),
                        pin.toByteArray(StandardCharsets.UTF_8)
                    )
                    if (!pinOk) {
                        sendHtml(out, buildLandingHtml(error = true), status = "403 Forbidden")
                        return
                    }
                    if (!archiveFile.exists()) {
                        sendText(out, "404 Not Found", "Archive not ready.")
                        return
                    }
                    streamArchiveFile(out, archiveFile, downloadFileName)
                }
                else -> sendText(out, "404 Not Found", "Not found")
            }
        }
    }

    private fun sendHtml(out: OutputStream, html: String, status: String = "200 OK") {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(headers)
        out.write(body)
        out.flush()
    }

    private fun sendText(out: OutputStream, status: String, text: String) {
        val body = text.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(headers)
        out.write(body)
        out.flush()
    }

    private fun streamArchiveFile(out: OutputStream, file: File, fileName: String) {
        val len = file.length()
        val safeName = fileName.replace("\"", "_")
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Disposition: attachment; filename=\"").append(safeName).append("\"\r\n")
            append("Content-Length: ").append(len).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(headers)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        out.flush()
    }

    private fun parseQueryParam(query: String, key: String): String? {
        for (pair in query.split('&')) {
            val k = pair.substringBefore('=')
            if (k == key) {
                return URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
        }
        return null
    }

    private fun buildLandingHtml(error: Boolean): String {
        val sizeMb = "%.1f MB".format(archiveFile.length() / (1024.0 * 1024.0))
        val errBanner = if (error) {
            """<div style="border:1px solid #F87171;background:#2A1215;color:#FCA5A5;padding:12px;border-radius:10px;margin-bottom:16px;">Invalid 6-digit PIN. Check the ETG screen on your phone.</div>"""
        } else ""
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>ETG — Export To GrapheneOS</title>
              <style>
                body{margin:0;background:#0B0D10;color:#E2E8F0;font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px;}
                .card{max-width:440px;width:100%;background:#12161C;border:1px solid #2B3340;border-radius:16px;padding:28px;}
                .badge{display:inline-block;font-family:monospace;font-size:12px;color:#5EA8FF;border:1px solid #2B3340;padding:4px 10px;border-radius:999px;margin-bottom:14px;}
                h1{margin:0 0 8px;font-size:22px;}
                p{color:#94A3B8;font-size:14px;line-height:1.5;}
                .meta{font-family:monospace;font-size:13px;background:#181D25;border:1px solid #2B3340;border-radius:10px;padding:12px;margin:16px 0;}
                input{width:100%;box-sizing:border-box;background:#0B0D10;color:#E2E8F0;border:1px solid #2B3340;border-radius:10px;padding:14px;font-family:monospace;font-size:20px;letter-spacing:6px;text-align:center;margin-bottom:14px;}
                button{width:100%;background:#5EA8FF;color:#0B0D10;font-weight:700;border:0;border-radius:10px;padding:14px;font-size:15px;cursor:pointer;}
              </style>
            </head>
            <body>
              <div class="card">
                <div class="badge">ETG // SOVEREIGN MIGRATION</div>
                <h1>Download GrapheneOS Backup</h1>
                <p>Enter the 6-digit PIN shown in ETG on your phone to download your migration archive over LAN or USB ADB cable.</p>
                $errBanner
                <div class="meta">File: $downloadFileName<br/>Size: $sizeMb</div>
                <form method="GET" action="/download">
                  <input type="password" name="pin" maxlength="6" placeholder="000000" required autofocus />
                  <button type="submit">Download Archive</button>
                </form>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
