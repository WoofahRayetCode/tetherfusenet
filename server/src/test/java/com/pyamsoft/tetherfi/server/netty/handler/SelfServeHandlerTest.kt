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

package com.pyamsoft.tetherfi.server.netty.handler

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.netty.TestSetup
import com.pyamsoft.tetherfi.server.netty.withLogging
import com.pyamsoft.tetherfi.server.proxy.session.address
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.http.Http1ProxyHandler
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInboundHandler
import io.netty.handler.codec.DecoderResult
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/**
 * A computer with no proxy settings asks the device itself for setup help. These tests make sure
 * those requests are answered by the proxy and never relayed, and that real proxy requests are not
 * disturbed.
 */
class SelfServeHandlerTest {

  /** What happened to a single request sent to the handler */
  private class Exchange(
      val responses: List<FullHttpResponse>,
      val connectAttempts: List<String>,
      val isChannelOpen: Boolean,
  ) {

    val response: FullHttpResponse?
      get() = responses.firstOrNull()

    @CheckResult
    fun body(): String {
      val content = requireNotNull(response).content()
      return content.toString(Charsets.UTF_8)
    }
  }

  @CheckResult
  private fun CoroutineScope.selfServeHandler(
      params: TestSetup.FactoryParams,
      connectAttempts: MutableList<String>,
      isBlocked: Boolean,
  ): ChannelInboundHandler {
    val realTcpSocketCreator = params.provideTcpChannelCreator()
    val recordingTcpSocketCreator =
        object : ChannelCreator {
          override fun bind(onChannelInitialized: (Channel) -> Unit): ChannelFuture =
              realTcpSocketCreator.bind(onChannelInitialized)

          override fun connect(
              hostName: String,
              port: Int,
              onChannelInitialized: (Channel) -> Unit,
          ): ChannelFuture {
            connectAttempts.add("$hostName:$port")
            return realTcpSocketCreator.connect(hostName, port, onChannelInitialized)
          }
        }

    val blocked =
        object : BlockedClients {
          override fun listenForBlocked(): Flow<Collection<TetherClient>> = flowOf(emptyList())

          override fun isBlocked(client: TetherClient): Boolean = isBlocked
        }

    return Http1ProxyHandler.factory(
            scope = this,
            isDebug = true,
            serverSocketTimeout = params.serverSocketTimeout,
            allowedClients = params.allowed,
            blockedClients = blocked,
            dispatchers = params.dispatchers,
            tcpSocketCreator = recordingTcpSocketCreator,
            selfServe = params.selfServe,
            isHttpProxyEnabled = params.isHttpEnabled,
            isSocksEnabled = params.isSocksEnabled,
        )
        .create(Unit)
  }

  @CheckResult
  private fun request(
      method: HttpMethod = HttpMethod.GET,
      uri: String,
      host: String? = LOCAL_HOST,
  ): DefaultFullHttpRequest =
      DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, Unpooled.EMPTY_BUFFER).apply {
        if (host != null) {
          headers().set(HttpHeaderNames.HOST, host)
        }
      }

  private suspend fun CoroutineScope.exchange(
      request: HttpRequest,
      isHttpEnabled: Boolean = true,
      isSocksEnabled: Boolean = true,
      isBlocked: Boolean = false,
      followUp: List<Any> = emptyList(),
  ): Exchange {
    val connectAttempts = mutableListOf<String>()
    var exchange: Exchange? = null

    withLogging {
      val context =
          TestSetup.withHandler(
              scope = this,
              isHttpEnabled = isHttpEnabled,
              isSocksEnabled = isSocksEnabled,
              factory = {
                this@exchange.selfServeHandler(
                    params = it,
                    connectAttempts = connectAttempts,
                    isBlocked = isBlocked,
                )
              },
              // TODO(Peter): Do we need test dispatchers?
              dispatchers = AppDispatchers.create(),
          )

      val channel = context.channel
      Http1ProxyHandler.applyChannelAttributes(
          channel = channel,
          client = context.resolver.ensure(channel.remoteAddress().address),
      )

      channel.apply {
        // One call: the handler closes the channel as soon as it has answered, and an embedded
        // channel complains about any flush after that
        writeInbound(request, *followUp.toTypedArray())
        runPendingTasks()
        checkException()
      }

      exchange =
          Exchange(
              // Closing the channel also flushes an empty buffer, which is not a response
              responses =
                  generateSequence { channel.readOutbound<Any>() }
                      .filterIsInstance<FullHttpResponse>()
                      .toList(),
              connectAttempts = connectAttempts.toList(),
              isChannelOpen = channel.isOpen,
          )
    }

    return requireNotNull(exchange)
  }

  @Test
  fun `test a request for the device itself is answered with the setup page`(): Unit =
      runBlockingWithDelays {
        val result = exchange(request(uri = "/"))

        val response = assertNotNull(result.response)
        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(
            response.headers().get(HttpHeaderNames.CONTENT_TYPE).orEmpty().startsWith("text/html")
        )
        assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION))
        assertEquals("no-store", response.headers().get(HttpHeaderNames.CACHE_CONTROL))
        assertEquals("nosniff", response.headers().get("X-Content-Type-Options"))
        assertNotNull(response.headers().get(HttpHeaderNames.CONTENT_SECURITY_POLICY))

        val body = result.body()
        assertTrue(body.contains("TetherFuseNet"))
        assertTrue(body.contains("http://$LOCAL_ADDRESS:$LOCAL_PORT/connect.sh"))
        assertEquals(
            response.content().readableBytes().toString(),
            response.headers().get(HttpHeaderNames.CONTENT_LENGTH),
        )

        // Nothing was relayed anywhere, and the connection is done
        assertTrue(result.connectAttempts.isEmpty())
        assertFalse(result.isChannelOpen)
      }

  @Test
  fun `test tfn json describes what the proxy offers`(): Unit = runBlockingWithDelays {
    val both = exchange(request(uri = "/tfn.json"), isHttpEnabled = true, isSocksEnabled = true)
    assertEquals(HttpResponseStatus.OK, assertNotNull(both.response).status())
    assertTrue(both.body().contains("\"socks5\":true"))
    assertTrue(both.body().contains("\"http\":true"))
    assertTrue(both.body().contains("\"host\":\"$LOCAL_ADDRESS\""))
    assertTrue(both.body().contains("\"port\":$LOCAL_PORT"))
    // The tests run with the default timeout
    assertTrue(both.body().contains("\"idle_timeout_s\":30"))

    val httpOnly =
        exchange(request(uri = "/tfn.json"), isHttpEnabled = true, isSocksEnabled = false)
    assertTrue(httpOnly.body().contains("\"socks5\":false"))
    assertTrue(httpOnly.body().contains("\"udp\":false"))
  }

  @Test
  fun `test the sing-box config points at the device and fills in every placeholder`(): Unit =
      runBlockingWithDelays {
        val result = exchange(request(uri = "/sing-box.json"))

        assertEquals(HttpResponseStatus.OK, assertNotNull(result.response).status())
        val body = result.body()
        assertTrue(body.contains("\"server\": \"$LOCAL_ADDRESS\""))
        assertTrue(body.contains("\"server_port\": $LOCAL_PORT"))
        assertFalse(body.contains("@@"))

        // The connect scripts look for exactly these to name the network interface of the computer
        assertTrue(body.contains("\"bind_interface\": \"\""))
        assertTrue(body.contains("\"auto_redirect\": false"))
      }

  @Test
  fun `test the sing-box config catches DNS, has a safe MTU and refuses IPv6`(): Unit =
      runBlockingWithDelays {
        val body = exchange(request(uri = "/sing-box.json")).body()

        assertTrue(body.contains("\"mtu\": 1400"))
        assertTrue(body.contains("\"port\": 53"))
        assertTrue(body.contains("\"action\": \"hijack-dns\""))
        assertTrue(body.contains("\"ip_version\": 6"))

        // By default UDP travels through the phone
        assertFalse(body.contains("\"network\": \"tcp\""))
        assertFalse(body.contains("\"network\": \"udp\""))
      }

  @Test
  fun `test the TCP only sing-box config turns UDP away`(): Unit = runBlockingWithDelays {
    val result = exchange(request(uri = "/sing-box.json?tcp=1"))

    assertEquals(HttpResponseStatus.OK, assertNotNull(result.response).status())
    val body = result.body()
    assertTrue(body.contains("\"network\": \"tcp\""))
    assertTrue(body.contains("\"network\": \"udp\", \"action\": \"reject\""))
    assertFalse(body.contains("@@"))

    // DNS is hijacked before UDP is turned away, so names still resolve
    assertTrue(body.indexOf("hijack-dns") < body.indexOf("\"network\": \"udp\""))
  }

  @Test
  fun `test the sing-box config is refused while SOCKS is turned off`(): Unit =
      runBlockingWithDelays {
        val result = exchange(request(uri = "/sing-box.json"), isSocksEnabled = false)

        assertEquals(HttpResponseStatus.CONFLICT, assertNotNull(result.response).status())
        assertTrue(result.body().contains("SOCKS is turned off"))
      }

  @Test
  fun `test the proxy auto-config lists only what is turned on and never falls back to direct`():
      Unit = runBlockingWithDelays {
    val both = exchange(request(uri = "/proxy.pac"), isHttpEnabled = true, isSocksEnabled = true)
    assertEquals(
        "application/x-ns-proxy-autoconfig",
        assertNotNull(both.response).headers().get(HttpHeaderNames.CONTENT_TYPE),
    )
    assertTrue(
        both
            .body()
            .contains(
                "return \"PROXY $LOCAL_ADDRESS:$LOCAL_PORT; SOCKS5 $LOCAL_ADDRESS:$LOCAL_PORT\";"
            )
    )

    val socksOnly =
        exchange(request(uri = "/proxy.pac"), isHttpEnabled = false, isSocksEnabled = true)
    assertTrue(socksOnly.body().contains("return \"SOCKS5 $LOCAL_ADDRESS:$LOCAL_PORT\";"))
    assertFalse(socksOnly.body().contains("PROXY $LOCAL_ADDRESS"))

    // The only DIRECT is for local names, the list itself has no fallback
    assertEquals(1, Regex("return \"DIRECT\";").findAll(both.body()).count())
  }

  @Test
  fun `test the connect scripts are served as text with the address filled in`(): Unit =
      runBlockingWithDelays {
        for (script in listOf("/connect.sh", "/connect.ps1")) {
          val result = exchange(request(uri = script))

          val response = assertNotNull(result.response)
          assertEquals(HttpResponseStatus.OK, response.status())
          assertEquals(
              "text/plain; charset=utf-8",
              response.headers().get(HttpHeaderNames.CONTENT_TYPE),
          )
          assertTrue(result.body().contains("$LOCAL_ADDRESS:$LOCAL_PORT"))
          assertFalse(result.body().contains("@@"))
        }
      }

  @Test
  fun `test an unknown path is not found`(): Unit = runBlockingWithDelays {
    val result = exchange(request(uri = "/nothing/here"))

    assertEquals(HttpResponseStatus.NOT_FOUND, assertNotNull(result.response).status())
    assertTrue(result.connectAttempts.isEmpty())
  }

  @Test
  fun `test a query string does not change which document is served`(): Unit =
      runBlockingWithDelays {
        val result = exchange(request(uri = "/tfn.json?cache=1"))

        assertEquals(HttpResponseStatus.OK, assertNotNull(result.response).status())
        assertTrue(result.body().contains("\"name\":\"TetherFuseNet\""))
      }

  @Test
  fun `test a browser using this proxy gets the same answer through a full URL`(): Unit =
      runBlockingWithDelays {
        val result = exchange(request(uri = "http://$LOCAL_ADDRESS:$LOCAL_PORT/tfn.json"))

        assertEquals(HttpResponseStatus.OK, assertNotNull(result.response).status())
        assertTrue(result.body().contains("\"socks5\":true"))
        assertTrue(result.connectAttempts.isEmpty())
      }

  @Test
  fun `test an old style request with no Host header is answered`(): Unit = runBlockingWithDelays {
    val result = exchange(request(uri = "/tfn.json", host = null))

    assertEquals(HttpResponseStatus.OK, assertNotNull(result.response).status())
    assertTrue(result.connectAttempts.isEmpty())
  }

  @Test
  fun `test HEAD says how long the body is but does not send one`(): Unit = runBlockingWithDelays {
    val get = exchange(request(uri = "/tfn.json"))
    val head = exchange(request(method = HttpMethod.HEAD, uri = "/tfn.json"))

    val response = assertNotNull(head.response)
    assertEquals(HttpResponseStatus.OK, response.status())
    assertEquals(0, response.content().readableBytes())
    assertEquals(
        get.body().toByteArray().size.toString(),
        response.headers().get(HttpHeaderNames.CONTENT_LENGTH),
    )
  }

  @Test
  fun `test other methods are not allowed`(): Unit = runBlockingWithDelays {
    val result = exchange(request(method = HttpMethod.POST, uri = "/tfn.json"))

    val response = assertNotNull(result.response)
    assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status())
    assertEquals("GET, HEAD", response.headers().get(HttpHeaderNames.ALLOW))
    assertTrue(result.connectAttempts.isEmpty())
  }

  @Test
  fun `test the body of an answered request is released and not queued`(): Unit =
      runBlockingWithDelays {
        val body = Unpooled.copiedBuffer("hello", Charsets.UTF_8)

        val post =
            DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/tfn.json").apply {
              headers().set(HttpHeaderNames.HOST, LOCAL_HOST)
            }
        val result = exchange(post, followUp = listOf(DefaultLastHttpContent(body)))

        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, assertNotNull(result.response).status())
        assertEquals(0, body.refCnt())
      }

  @Test
  fun `test a request for another host is still relayed`(): Unit = runBlockingWithDelays {
    val other = exchange(request(uri = "/", host = "192.168.2.1:8096"))
    assertNull(other.response)
    assertEquals(listOf("192.168.2.1:8096"), other.connectAttempts)

    val absolute =
        exchange(request(uri = "http://192.168.2.1:8096/hello", host = "192.168.2.1:8096"))
    assertNull(absolute.response)
    assertEquals(listOf("192.168.2.1:8096"), absolute.connectAttempts)
  }

  @Test
  fun `test only the exact address and port of the device are served`(): Unit =
      runBlockingWithDelays {
        // The same address on another port, or on the default port, is some other server. This
        // address is loopback, which the proxy never relays to, so the answer is an error and
        // above all NOT a document.
        for (host in listOf(LOCAL_ADDRESS, "$LOCAL_ADDRESS:9999")) {
          val result = exchange(request(uri = "/tfn.json", host = host))

          assertEquals(HttpResponseStatus.BAD_GATEWAY, assertNotNull(result.response).status())
          assertFalse(result.body().contains("TetherFuseNet"))
          assertTrue(result.connectAttempts.isEmpty())
        }
      }

  @Test
  fun `test a blocked client gets nothing from the device`(): Unit = runBlockingWithDelays {
    val result = exchange(request(uri = "/tfn.json"), isBlocked = true)

    assertEquals(HttpResponseStatus.BAD_GATEWAY, assertNotNull(result.response).status())
    assertFalse(result.body().contains("TetherFuseNet"))
    assertTrue(result.connectAttempts.isEmpty())
  }

  @Test
  fun `test something that is not readable HTTP is a bad request, not a page`(): Unit =
      runBlockingWithDelays {
        // What Netty hands over for garbage, or for a TLS handshake sent to this port
        val garbage =
            DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/bad-request").apply {
              setDecoderResult(DecoderResult.failure(IllegalArgumentException("not http")))
            }
        val result = exchange(garbage)

        assertEquals(HttpResponseStatus.BAD_REQUEST, assertNotNull(result.response).status())
        assertTrue(result.connectAttempts.isEmpty())
        assertFalse(result.isChannelOpen)
      }

  @Test
  fun `test with only SOCKS turned on the device still answers but nothing is relayed`(): Unit =
      runBlockingWithDelays {
        val own = exchange(request(uri = "/tfn.json"), isHttpEnabled = false, isSocksEnabled = true)
        assertEquals(HttpResponseStatus.OK, assertNotNull(own.response).status())
        assertTrue(own.body().contains("\"http\":false"))

        val relay =
            exchange(
                request(uri = "http://192.168.2.1/", host = "192.168.2.1"),
                isHttpEnabled = false,
                isSocksEnabled = true,
            )
        assertEquals(HttpResponseStatus.FORBIDDEN, assertNotNull(relay.response).status())
        assertTrue(relay.connectAttempts.isEmpty())
        assertFalse(relay.isChannelOpen)
      }

  @Test
  fun `test CONNECT is never answered with a document`(): Unit = runBlockingWithDelays {
    val result =
        exchange(
            request(
                method = HttpMethod.CONNECT,
                uri = "$LOCAL_ADDRESS:$LOCAL_PORT",
                host = "$LOCAL_ADDRESS:$LOCAL_PORT",
            )
        )

    // Goes down the CONNECT path, where a loopback destination is refused
    assertEquals(HttpResponseStatus.BAD_GATEWAY, assertNotNull(result.response).status())
    assertTrue(result.connectAttempts.isEmpty())
  }

  private companion object {
    // The fake channel in TestSetup says it is listening here
    const val LOCAL_ADDRESS = "127.0.0.1"
    const val LOCAL_PORT = 8228
    const val LOCAL_HOST = "$LOCAL_ADDRESS:$LOCAL_PORT"
  }
}
