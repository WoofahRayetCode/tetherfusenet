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
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real sockets through a real proxy: a SOCKS5 client asks for a UDP association and sends datagrams
 * to an echo server, the same way a tunnel such as sing-box or tun2socks does for every UDP flow.
 *
 * The client, the stranger and the echo server each have their own loopback address, because the
 * proxy treats a packet from the client's own address as the client's. An echo server on the
 * client's address could not be told apart from the client.
 */
class UdpAssociateTest {

  /** Echoes every datagram back to whoever sent it */
  private class EchoServer(
      address: InetAddress,
  ) : AutoCloseable {

    val socket = DatagramSocket(0, address)

    init {
      thread(isDaemon = true) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (!socket.isClosed) {
          val packet = DatagramPacket(buffer, buffer.size)
          try {
            socket.receive(packet)
            socket.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
          } catch (_: SocketException) {
            // Closed, we are done
            break
          }
        }
      }
    }

    override fun close() {
      socket.close()
    }
  }

  /** A SOCKS5 connection that has been granted a UDP relay */
  private class Association(
      proxyPort: Int,
  ) : AutoCloseable {

    private val control = Socket(InetAddress.getByName(CLIENT_HOST), proxyPort)
    val relay: InetSocketAddress

    init {
      control.soTimeout = IO_TIMEOUT_MILLIS

      val output = control.getOutputStream()
      val input = DataInputStream(control.getInputStream())

      // Offer "no authentication"
      output.write(byteArrayOf(SOCKS_VERSION, 1, 0))
      output.flush()
      val method = ByteArray(2)
      input.readFully(method)
      check(method[1] == 0.toByte()) { "No authentication was not accepted: ${method[1]}" }

      // UDP ASSOCIATE, we do not know our own address yet so say 0.0.0.0:0 like real clients do
      output.write(
          byteArrayOf(SOCKS_VERSION, COMMAND_UDP_ASSOCIATE, 0, ADDRESS_IPV4, 0, 0, 0, 0, 0, 0)
      )
      output.flush()
      val head = ByteArray(4)
      input.readFully(head)
      check(head[1] == 0.toByte()) { "UDP ASSOCIATE was refused: ${head[1]}" }
      check(head[3] == ADDRESS_IPV4) { "Expected an IPv4 relay address: ${head[3]}" }

      val address = ByteArray(4)
      input.readFully(address)
      val port = ByteArray(2)
      input.readFully(port)
      relay =
          InetSocketAddress(
              InetAddress.getByAddress(address),
              ((port[0].toInt() and BYTE_MASK) shl BYTE_BITS) or (port[1].toInt() and BYTE_MASK),
          )
    }

    /** The proxy ends the association by closing this connection */
    @CheckResult
    fun isOpen(): Boolean {
      val original = control.soTimeout
      control.soTimeout = PEEK_TIMEOUT_MILLIS
      return try {
        // Nothing is ever sent to us on this connection, so end-of-stream means it was closed
        control.getInputStream().read() != END_OF_STREAM
      } catch (_: SocketTimeoutException) {
        true
      } catch (_: SocketException) {
        false
      } finally {
        control.soTimeout = original
      }
    }

    override fun close() {
      control.close()
    }
  }

  @CheckResult
  private fun bindLoopback(address: String): DatagramSocket? =
      try {
        DatagramSocket(0, InetAddress.getByName(address)).apply { soTimeout = IO_TIMEOUT_MILLIS }
      } catch (_: SocketException) {
        // Not every operating system has more than one loopback address
        null
      }

  @CheckResult
  private fun startEcho(): EchoServer? =
      try {
        EchoServer(InetAddress.getByName(ECHO_HOST))
      } catch (_: SocketException) {
        null
      }

  @CheckResult
  private fun socksDatagram(destination: InetSocketAddress, payload: ByteArray): DatagramPacket {
    val port = destination.port
    val bytes =
        byteArrayOf(0, 0, 0, ADDRESS_IPV4) +
            destination.address.address +
            byteArrayOf((port shr BYTE_BITS).toByte(), port.toByte()) +
            payload
    return DatagramPacket(bytes, bytes.size)
  }

  /** Waits for a datagram and returns its payload without the SOCKS5 header, or null */
  @CheckResult
  private fun receivePayload(socket: DatagramSocket): ByteArray? {
    val packet = DatagramPacket(ByteArray(BUFFER_SIZE), BUFFER_SIZE)
    return try {
      socket.receive(packet)
      packet.data.copyOfRange(SOCKS_UDP_HEADER_SIZE, packet.length)
    } catch (_: SocketTimeoutException) {
      null
    }
  }

  @CheckResult private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  private suspend fun withProxy(block: suspend (proxyPort: Int) -> Unit) {
    val port = freePort()
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
  fun `test datagrams are relayed to a server and back`(): Unit =
      runBlockingWithDelays(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        val client = bindLoopback(CLIENT_HOST)
        val echo = startEcho()
        assumeTrue(
            "needs the loopback addresses $CLIENT_HOST and $ECHO_HOST",
            client != null && echo != null,
        )

        try {
          withProxy { proxyPort ->
            Association(proxyPort).use { association ->
              val socket = requireNotNull(client)
              val destination = InetSocketAddress(ECHO_HOST, requireNotNull(echo).socket.localPort)

              // Many in a row, a tunnel does not send one datagram and then wait
              val wanted = (0 until DATAGRAM_COUNT).map { "datagram-$it" }
              for (payload in wanted) {
                socket.send(
                    socksDatagram(destination, payload.toByteArray()).apply {
                      socketAddress = association.relay
                    }
                )
              }

              val received = mutableListOf<String>()
              repeat(DATAGRAM_COUNT) {
                received.add(assertNotNull(receivePayload(socket)).toString(Charsets.UTF_8))
              }

              // UDP promises no order, but nothing may be lost or invented
              assertEquals(wanted.sorted(), received.sorted())
              assertNull(receivePayload(socket))
            }
          }
        } finally {
          client?.close()
          echo?.close()
        }
      }

  @Test
  fun `test a malformed datagram is dropped and does not end the association`(): Unit =
      runBlockingWithDelays(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        val client = bindLoopback(CLIENT_HOST)
        val echo = startEcho()
        assumeTrue(
            "needs the loopback addresses $CLIENT_HOST and $ECHO_HOST",
            client != null && echo != null,
        )

        try {
          withProxy { proxyPort ->
            Association(proxyPort).use { association ->
              val socket = requireNotNull(client)
              val destination = InetSocketAddress(ECHO_HOST, requireNotNull(echo).socket.localPort)
              val payload = "still here".toByteArray()

              fun sendValid() {
                socket.send(
                    socksDatagram(destination, payload).apply { socketAddress = association.relay }
                )
              }

              sendValid()
              assertContentEquals(payload, receivePayload(socket))

              // Too short to hold even the SOCKS5 header, and headers that stop in the middle of
              // the address and right before the port. None can be answered and none may take the
              // association down with it.
              for (malformed in
                  listOf(
                      byteArrayOf(0, 0),
                      byteArrayOf(0, 0, 0, ADDRESS_IPV4, 127),
                      byteArrayOf(0, 0, 0, ADDRESS_IPV4, 127, 0, 0, 2),
                  )) {
                socket.send(DatagramPacket(malformed, malformed.size, association.relay))
              }
              assertNull(receivePayload(socket))
              assertTrue(association.isOpen(), "A malformed datagram closed the association")

              sendValid()
              assertContentEquals(payload, receivePayload(socket))
            }
          }
        } finally {
          client?.close()
          echo?.close()
        }
      }

  @Test
  fun `test a stranger can neither end the association nor take over its return path`(): Unit =
      runBlockingWithDelays(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        val client = bindLoopback(CLIENT_HOST)
        val stranger = bindLoopback(STRANGER_HOST)
        val echo = startEcho()
        assumeTrue(
            "needs the loopback addresses $CLIENT_HOST, $STRANGER_HOST and $ECHO_HOST",
            client != null && stranger != null && echo != null,
        )

        try {
          withProxy { proxyPort ->
            Association(proxyPort).use { association ->
              val destination = InetSocketAddress(ECHO_HOST, requireNotNull(echo).socket.localPort)
              val payload = "for the client".toByteArray()

              // Somebody that is not the client talks to the relay first
              requireNotNull(stranger)
                  .send(
                      socksDatagram(destination, payload).apply {
                        socketAddress = association.relay
                      }
                  )
              assertNull(receivePayload(stranger), "A stranger got an answer")
              assertTrue(association.isOpen(), "A stranger closed the association")

              // The client is unaffected, and the answer goes to the client and not the stranger
              requireNotNull(client)
                  .send(
                      socksDatagram(destination, payload).apply {
                        socketAddress = association.relay
                      }
                  )
              assertContentEquals(payload, receivePayload(client))
              assertNull(receivePayload(stranger), "The stranger took over the return path")
            }
          }
        } finally {
          client?.close()
          stranger?.close()
          echo?.close()
        }
      }

  private companion object {
    const val CLIENT_HOST = "127.0.0.1"
    const val ECHO_HOST = "127.0.0.2"
    const val STRANGER_HOST = "127.0.0.3"

    const val SOCKS_VERSION: Byte = 5
    const val COMMAND_UDP_ASSOCIATE: Byte = 3
    const val ADDRESS_IPV4: Byte = 1
    const val SOCKS_UDP_HEADER_SIZE = 10

    const val BYTE_BITS = 8
    const val BYTE_MASK = 0xFF
    const val END_OF_STREAM = -1

    const val BUFFER_SIZE = 2048
    const val IO_TIMEOUT_MILLIS = 1000
    const val PEEK_TIMEOUT_MILLIS = 200
    const val POLL_MILLIS = 20L
    const val TEST_TIMEOUT_SECONDS = 30
    const val DATAGRAM_COUNT = 20
  }
}
