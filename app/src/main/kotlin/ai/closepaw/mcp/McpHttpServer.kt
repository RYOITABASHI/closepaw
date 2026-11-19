package ai.closepaw.mcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Minimal HTTP/1.1 transport for [McpHandler]: one request per connection (`Connection: close`),
 * `Content-Length` bodies only, JSON responses. No dependency on an HTTP library (same reasoning as
 * Shelly's dependency-free server). Bounded everywhere: header block [MAX_HEADER_BYTES], body
 * [MAX_BODY_BYTES], [WORKERS] worker threads + a small queue (excess connections are closed),
 * [READ_TIMEOUT_MS] socket read timeout.
 *
 * Pure JVM; [start] binds [bindAddress]:[port] (port 0 = ephemeral, used by tests).
 */
class McpHttpServer(
    private val handler: McpHandler,
    private val port: Int = McpHandler.DEFAULT_PORT,
    private val bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val onError: (String) -> Unit = {},
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private var pool: ThreadPoolExecutor? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** The bound port (useful when started with port 0), or -1 when stopped. */
    val boundPort: Int get() = serverSocket?.takeIf { !it.isClosed }?.localPort ?: -1

    /** Binds and starts accepting. Throws [IOException] if the port cannot be bound. */
    @Synchronized
    fun start() {
        if (isRunning) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(bindAddress, port))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        val executor = ThreadPoolExecutor(
            WORKERS, WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE),
        ) { r -> Thread(r, "closepaw-mcp-worker").apply { isDaemon = true } }
        serverSocket = socket
        pool = executor
        acceptThread = Thread({ acceptLoop(socket, executor) }, "closepaw-mcp-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        acceptThread = null
        pool?.shutdownNow()
        pool = null
    }

    private fun acceptLoop(socket: ServerSocket, executor: ThreadPoolExecutor) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break // closed by stop()
            } catch (e: IOException) {
                onError("accept failed: ${e.javaClass.simpleName}")
                continue
            }
            try {
                executor.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                closeQuietly(client)
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(s.getInputStream())
                val response = when (val parsed = readRequest(input)) {
                    is Parsed.Ok -> handler.handle(parsed.request)
                    is Parsed.Error -> McpHttpResponse(parsed.status, """{"error":"${parsed.message}"}""")
                }
                write(s, response)
            } catch (e: IOException) {
                // Client went away / timed out: nothing to answer.
            } catch (e: Exception) {
                onError("request failed: ${e.javaClass.simpleName}")
                try {
                    write(s, McpHttpResponse(500, """{"error":"internal error"}"""))
                } catch (_: IOException) {
                }
            }
        }
    }

    private sealed interface Parsed {
        data class Ok(val request: McpHttpRequest) : Parsed
        data class Error(val status: Int, val message: String) : Parsed
    }

    private fun readRequest(input: InputStream): Parsed {
        val head = readHead(input) ?: return Parsed.Error(431, "request header too large")
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: return Parsed.Error(400, "bad request")
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) return Parsed.Error(400, "bad request")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) return Parsed.Error(400, "bad request")
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            // A repeated header is ambiguous for auth / session checks: refuse it.
            if (headers.containsKey(name)) return Parsed.Error(400, "duplicate header")
            headers[name] = line.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"] != null) return Parsed.Error(411, "length required")
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: return Parsed.Error(400, "bad request") } ?: 0
        if (length < 0) return Parsed.Error(400, "bad request")
        if (length > MAX_BODY_BYTES) return Parsed.Error(413, "payload too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return Parsed.Error(400, "bad request")
            read += n
        }
        return Parsed.Ok(
            McpHttpRequest(
                method = requestLine[0].uppercase(Locale.ROOT),
                path = requestLine[1],
                headers = headers,
                body = String(body, Charsets.UTF_8),
            )
        )
    }

    /** Reads up to and excluding the blank line; null if it exceeds [MAX_HEADER_BYTES]. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0 // matched bytes of "\r\n\r\n"
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("eof in header")
            buf.write(b)
            if (buf.size() > MAX_HEADER_BYTES) return null
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        val bytes = buf.toByteArray()
        return String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1)
    }

    private fun write(socket: Socket, response: McpHttpResponse) {
        val body = response.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
        if (response.body != null) sb.append("Content-Type: application/json\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n")
        response.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
        const val READ_TIMEOUT_MS = 15_000
        const val WORKERS = 4
        const val QUEUE = 8

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            else -> if (status >= 500) "Internal Server Error" else "Status"
        }
    }
}
