package com.topjohnwu.magisk.ui.module

import com.topjohnwu.superuser.nio.ExtendedFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale

class WebUiServer(private val root: ExtendedFile) : Thread("webui") {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort

    init {
        isDaemon = true
    }

    override fun run() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            try {
                serve(client)
            } catch (_: Exception) {
            } finally {
                client.close()
            }
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 8000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val request = reader.readLine() ?: return
        val path = request.split(" ").getOrNull(1) ?: "/"
        val relative = URLDecoder.decode(path.substringBefore("?"), "UTF-8")
            .trimStart('/')
            .ifEmpty { "index.html" }
        if (relative.contains("..")) {
            write(client, 403, "text/plain", "Forbidden".toByteArray())
            return
        }
        val file = resolve(relative)
        if (file == null || !file.exists() || file.isDirectory) {
            write(client, 404, "text/plain", "Not found".toByteArray())
            return
        }
        val bytes = file.newInputStream().use { it.readBytes() }
        write(client, 200, mime(file.name), bytes)
    }

    private fun resolve(relative: String): ExtendedFile? {
        var current = root
        for (part in relative.split('/')) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") return null
            current = current.getChildFile(part)
        }
        return current
    }

    private fun write(client: Socket, code: Int, type: String, body: ByteArray) {
        val status = if (code == 200) "OK" else if (code == 403) "Forbidden" else "Not Found"
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun mime(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}
