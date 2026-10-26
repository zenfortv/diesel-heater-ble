package io.github.zenfortv.dieselheater

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Tiny HTTP + WebSocket server on 127.0.0.1 with the same /api and /ws as server.py, so the page
 *  in web/ runs in its "Box" mode inside the app's WebView. Only reachable from this phone. */
class WebServer(
    private val ctx: Context,
    private val api: (method: String, path: String, body: JSONObject) -> Any?,
    private val hello: () -> String,
) {
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null

    fun start(port: Int) {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        server = ss
        thread(name = "http") {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { try { handle(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} } }
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    fun broadcast(text: String) {
        for (c in clients) try { sendFrame(c, text) } catch (_: Exception) { clients.remove(c) }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun handle(s: Socket) {
        val inp = BufferedInputStream(s.getInputStream())
        val out = BufferedOutputStream(s.getOutputStream())
        val req = readLine(inp)?.split(" ") ?: return
        if (req.size < 2) return
        val method = req[0]
        val path = req[1].substringBefore('?')
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        if (path == "/ws" && headers["upgrade"]?.lowercase() == "websocket") return websocket(inp, out, headers)
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var r = 0
        while (r < len) { val n = inp.read(body, r, len - r); if (n < 0) break; r += n }

        if (path.startsWith("/api/")) {
            val (code, json) = try {
                val b = if (len > 0) JSONObject(String(body, Charsets.UTF_8)) else JSONObject()
                val res = api(method, path.removePrefix("/api/"), b)
                if (res == null) 404 to """{"error":"not found"}""" else 200 to res.toString()
            } catch (e: Exception) { 400 to JSONObject().put("error", e.message ?: "error").toString() }
            return respond(out, code, "application/json", json.toByteArray())
        }
        val name = if (path == "/") "index.html" else path.removePrefix("/")
        if (name.contains("..") || name.contains("/")) return respond(out, 404, "text/plain", "not found".toByteArray())
        val bytes = try { ctx.assets.open(name).use { it.readBytes() } } catch (_: Exception) {
            return respond(out, 404, "text/plain", "not found".toByteArray())
        }
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"; "js" -> "text/javascript"; "json" -> "application/json"
            "svg" -> "image/svg+xml"; "png" -> "image/png"; else -> "application/octet-stream"
        }
        respond(out, 200, type, bytes)
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Request" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun websocket(inp: InputStream, out: OutputStream, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: return
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()), Base64.NO_WRAP)
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        out.flush()
        clients.add(out)
        try {
            sendFrame(out, hello())
            // the page never sends anything: read and drop frames until it closes
            while (true) {
                val b0 = inp.read(); val b1 = inp.read()
                if (b0 < 0 || b1 < 0 || b0 and 0x0f == 8) break
                var n = (b1 and 0x7f).toLong()
                if (n == 126L) n = ((inp.read() shl 8) or inp.read()).toLong()
                else if (n == 127L) { n = 0; repeat(8) { n = (n shl 8) or inp.read().toLong() } }
                if (b1 and 0x80 != 0) n += 4
                while (n > 0) { val k = inp.skip(n); if (k <= 0) { if (inp.read() < 0) return; n-- } else n -= k }
            }
        } finally { clients.remove(out) }
    }

    private fun sendFrame(out: OutputStream, text: String) {
        val p = text.toByteArray()
        synchronized(out) {
            out.write(0x81)
            when {
                p.size < 126 -> out.write(p.size)
                p.size < 65536 -> { out.write(126); out.write(p.size shr 8); out.write(p.size and 255) }
                else -> { out.write(127); for (i in 7 downTo 0) out.write(((p.size.toLong() shr (8 * i)) and 255).toInt()) }
            }
            out.write(p)
            out.flush()
        }
    }
}
