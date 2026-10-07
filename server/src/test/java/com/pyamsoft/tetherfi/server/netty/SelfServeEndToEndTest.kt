/*
 * Copyright 2026 pyamsoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pyamsoft.tetherfi.server.netty

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Test

/**
 * A real server and a plain TCP client that speaks HTTP by hand, the way a browser or curl on a
 * computer with no proxy settings would. Unlike the handler tests this goes through the real
 * protocol sniffing and the real HTTP codec.
 */
class SelfServeEndToEndTest {

  /** Everything the server sent back before it closed the connection */
  private class Reply(
      val head: String,
      val body: String,
  ) {

    val statusLine: String
      get() = head.lineSequence().first()

    @CheckResult
    fun header(name: String): String? =
        head
            .lineSequence()
            .drop(1)
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
  }

  /** Sends [request] as is and reads until the server closes the connection */
  @CheckResult
  private fun exchange(port: Int, request: ByteArray): Reply {
    Socket(InetAddress.getByName(HOST), port).use { socket ->
      socket.soTimeout = IO_TIMEOUT_MILLIS
      socket.getOutputStream().apply {
        write(request)
        flush()
      }

      // The server closes the connection after answering, so this ends by itself
      val raw = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
      val head = raw.substringBefore("\r\n\r\n")
      val body = raw.substringAfter("\r\n\r\n", missingDelimiterValue = "")
      return Reply(head = head, body = body)
    }
  }

  @CheckResult
  private fun http(port: Int, method: String, path: String, body: String = ""): Reply {
    val request =
        "$method $path HTTP/1.1\r\n" +
            "Host: $HOST:$port\r\n" +
            (if (body.isEmpty()) "" else "Content-Length: ${body.length}\r\n") +
            "\r\n" +
            body
    return exchange(port, request.toByteArray(Charsets.ISO_8859_1))
  }

  private suspend fun withProxy(block: suspend (port: Int) -> Unit) {
    val port = ServerSocket(0).use { it.localPort }
    TestSetup.withNetty(
        port = port,
        // TODO(Peter): Do we need test dispatchers?
        dispatchers = AppDispatchers.create(),
        isLoggingEnabled = false,
    ) {
      // The server is started by another thread, wait until it is listening
      while (openCount.get() < 1) {
        delay(POLL_MILLIS.milliseconds)
      }

      block(port)
    }
  }

  @Test
  fun `test the real server answers a plain HTTP client that has no proxy settings`(): Unit =
      runBlockingWithDelays(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        withProxy { port ->
          val reply = http(port, "GET", "/tfn.json")

          assertEquals("HTTP/1.1 200 OK", reply.statusLine)
          assertEquals("application/json; charset=utf-8", reply.header("Content-Type"))
          assertEquals("close", reply.header("Connection"))
          assertEquals(reply.body.length.toString(), reply.header("Content-Length"))
          assertTrue(reply.body.contains("\"host\":\"$HOST\""))
          assertTrue(reply.body.contains("\"port\":$port"))
          assertTrue(reply.body.contains("\"socks5\":true"))
        }
      }

  @Test
  fun `test the real server serves the setup page and every document it links to`(): Unit =
      runBlockingWithDelays(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        withProxy { port ->
          val page = http(port, "GET", "/")
          assertEquals("HTTP/1.1 200 OK", page.statusLine)
          assertTrue(page.header("Content-Type").orEmpty().startsWith("text/html"))
          assertTrue(page.body.contains("http://$HOST:$port/connect.sh"))

          for (path in
              listOf("/tfn.json", "/sing-box.json", "/connect.sh", "/connect.ps1", "/proxy.pac")) {
            val document = http(port, "GET", path)
            assertEquals("HTTP/1.1 200 OK", document.statusLine, path)
            assertTrue(document.body.isNotBlank(), path)
            assertFalse(document.body.contains("@@"), "$path still has a placeholder in it")
          }
        }
      }

  @Test
  fun `test HEAD sends headers and no body over a real connection`(): Unit =
      runBlockingWithDelays(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        withProxy { port ->
          val get = http(port, "GET", "/tfn.json")
          val head = http(port, "HEAD", "/tfn.json")

          assertEquals("HTTP/1.1 200 OK", head.statusLine)
          assertEquals(get.body.length.toString(), head.header("Content-Length"))
          assertEquals("", head.body)
        }
      }

  @Test
  fun `test the real server refuses to be written to`(): Unit =
      runBlockingWithDelays(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        withProxy { port ->
          val reply = http(port, "POST", "/tfn.json", body = "hello")

          assertEquals("HTTP/1.1 405 Method Not Allowed", reply.statusLine)
          assertEquals("GET, HEAD", reply.header("Allow"))
        }
      }

  @Test
  fun `test a TLS handshake sent to the port gets a bad request, not a page`(): Unit =
      runBlockingWithDelays(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        withProxy { port ->
          // The start of a TLS ClientHello, which is what a browser sends to
          // https://<address>:<port>
          val hello =
              byteArrayOf(
                  0x16,
                  0x03,
                  0x01,
                  0x00,
                  0x05,
                  0x01,
                  0x00,
                  0x00,
                  0x01,
                  0x00,
                  0x0d,
                  0x0a,
                  0x0d,
                  0x0a,
              )
          val reply = exchange(port, hello)

          assertTrue(reply.statusLine.contains("400"), reply.statusLine)
          assertFalse(reply.body.contains("TetherFuseNet"))
        }
      }

  private companion object {
    const val HOST = "127.0.0.1"
    const val IO_TIMEOUT_MILLIS = 5000
    const val POLL_MILLIS = 20L
    const val TEST_TIMEOUT_SECONDS = 30
  }
}
