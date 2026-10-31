package com.valhalla.tavernforge.data.network.gemini

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GeminiReplayGuardTest {
    /** Loopback-only server, no credentials: checks the actual production OkHttp send guard. */
    @Test fun retryAfterZeroCannotSendASecondHttpRequest() {
        val received = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 1500
            val serving = worker.submit {
                repeat(2) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { return@submit }
                    socket.use {
                        it.soTimeout = 1000
                        val reader = it.getInputStream().bufferedReader()
                        val line = try { reader.readLine() } catch (_: SocketTimeoutException) { null }
                        if (line != null) {
                            received.incrementAndGet()
                            while (true) { val header = reader.readLine(); if (header.isNullOrEmpty()) break }
                            it.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\n" +
                                "Content-Length: 0\r\nRetry-After: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
            val client = OkHttpClient.Builder().singleGeminiAttempt().callTimeout(3, TimeUnit.SECONDS).build()
            try {
                val address = server.inetAddress.hostAddress!!.let { if (it.contains(':')) "[$it]" else it }
                client.newCall(Request.Builder().url("http://$address:${server.localPort}/synthetic-only").build()).execute().use {
                    assertEquals(503, it.code)
                }
            } catch (_: IOException) {
                // An attempted internal follow-up is rejected before any second HTTP request.
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
            serving.get(5, TimeUnit.SECONDS)
            assertEquals(1, received.get())
        }
        worker.shutdownNow()
    }
}
