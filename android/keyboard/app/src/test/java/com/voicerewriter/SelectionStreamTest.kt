package com.voicerewriter

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectionStreamTest {
    private fun runStream(events: String, slow: Boolean = false): String {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() }
                    val data = events.toByteArray()
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(header.toByteArray()); write(data); flush() }
                }
            }
            val settings = Settings(provider = "custom", model = "fixture", customEndpoint = "http://127.0.0.1:${server.localPort}/v1/chat/completions", apiKey = "synthetic-fixture", antiAI = false)
            val result = runBlocking {
                withTimeout(5000) {
                    val out = StringBuilder()
                    RewriteEngine.streamInstruction(settings, "Repeat the sentence twice", "Hello.").collect {
                        if (slow) delay(2)
                        out.append(it)
                    }
                    out.toString()
                }
            }
            worker.join(1000)
            return result
        }
    }
    private val delta = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello. Hello.\"},\"finish_reason\":null}]}\n\n"
    private val finish = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
    @Test fun completeStreamPreservesRequestedRepetition() { assertEquals("Hello. Hello.", runStream(delta + finish)) }
    @Test fun incompleteStreamCannotReplaceSelection() {
        assertThrows(Exception::class.java) { runStream(delta) }
    }
    @Test fun errorAfterPartialTextCannotReplaceSelection() {
        assertThrows(Exception::class.java) { runStream(delta + "data: {\"error\":{\"message\":\"fixture failure\"}}\n\ndata: [DONE]\n\n") }
    }
    @Test fun lengthLimitedOutputCannotReplaceSelection() {
        assertThrows(Exception::class.java) { runStream(delta + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n") }
    }
    @Test fun slowCollectorDoesNotLoseChunks() {
        val piece = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n"
        assertEquals("x".repeat(250), runStream(piece.repeat(250) + finish, slow = true))
    }
}
