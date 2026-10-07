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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler.selfserve

import androidx.annotation.CheckResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the documents that let a computer with NO proxy settings find and use the proxy.
 *
 * The documents are templates under `resources/tfn/`. The only things ever put into them are the
 * address and port the client already used to reach this device, the enabled protocols, and the app
 * version, so nothing a client sends is ever echoed back.
 */
internal class DefaultSelfServeResponder
internal constructor(
    appVersion: String,
) : SelfServeResponder {

  // Only plain characters can ever end up inside a document
  private val version =
      appVersion
          .filter { it.isLetterOrDigit() || it in VERSION_PUNCTUATION }
          .ifBlank {
            UNKNOWN_VERSION
          }

  private val templates = ConcurrentHashMap<String, String>()

  @CheckResult
  private fun template(name: String): String? {
    val cached = templates[name]
    if (cached != null) {
      return cached
    }

    val loaded =
        DefaultSelfServeResponder::class
            .java
            .getResourceAsStream("$RESOURCE_DIRECTORY/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    if (loaded != null) {
      templates[name] = loaded
    }
    return loaded
  }

  @CheckResult
  private fun fill(
      name: String,
      contentType: String,
      request: SelfServeRequest,
      extra: Map<String, String> = emptyMap(),
  ): SelfServeResponse {
    val raw = template(name)
    if (raw == null) {
      return SelfServeResponse(
          status = STATUS_SERVER_ERROR,
          contentType = TYPE_TEXT,
          body = "TetherFuseNet is missing a bundled file: $name",
      )
    }

    var body =
        raw.replace(KEY_HOST, request.host)
            .replace(KEY_PORT, request.port.toString())
            .replace(KEY_VERSION, version)

    for ((key, value) in extra) {
      body = body.replace(key, value)
    }

    return SelfServeResponse(
        status = STATUS_OK,
        contentType = contentType,
        body = body,
    )
  }

  @CheckResult
  private fun describe(request: SelfServeRequest): SelfServeResponse =
      SelfServeResponse(
          status = STATUS_OK,
          contentType = TYPE_JSON,
          // Compact on purpose, the connect scripts look for "socks5":true
          body =
              "{" +
                  "\"name\":\"TetherFuseNet\"," +
                  "\"api\":$API_VERSION," +
                  "\"version\":\"$version\"," +
                  "\"host\":\"${request.host}\"," +
                  "\"port\":${request.port}," +
                  "\"http\":${request.isHttpEnabled}," +
                  "\"socks5\":${request.isSocksEnabled}," +
                  "\"udp\":${request.isSocksEnabled}," +
                  "\"idle_timeout_s\":${request.idleTimeoutSeconds}" +
                  "}",
      )

  /**
   * If [isTcpOnly], the config carries TCP only and turns UDP away, so applications that try QUIC
   * fall back to TCP. DNS is not UDP traffic for this purpose: it is answered by sing-box itself.
   */
  @CheckResult
  private fun singBox(request: SelfServeRequest, isTcpOnly: Boolean): SelfServeResponse =
      if (request.isSocksEnabled) {
        fill(
            name = "sing-box.json",
            contentType = TYPE_JSON,
            request = request,
            extra =
                mapOf(
                    KEY_SOCKS_NETWORK to if (isTcpOnly) "\"network\": \"tcp\"," else "",
                    KEY_UDP_REJECT_RULE to
                        if (isTcpOnly) "{ \"network\": \"udp\", \"action\": \"reject\" }," else "",
                ),
        )
      } else {
        SelfServeResponse(
            status = STATUS_CONFLICT,
            contentType = TYPE_TEXT,
            body = SOCKS_DISABLED,
        )
      }

  @CheckResult
  private fun proxyAutoConfig(request: SelfServeRequest): SelfServeResponse {
    val endpoint = "${request.host}:${request.port}"

    // No DIRECT fallback: if the proxy goes away the browser must fail, not quietly use another
    // route (over USB tethering that route would be the carrier visible one)
    val proxies = buildList {
      if (request.isHttpEnabled) {
        add("PROXY $endpoint")
      }
      if (request.isSocksEnabled) {
        add("SOCKS5 $endpoint")
      }
    }

    return fill(
        name = "proxy.pac",
        contentType = TYPE_PAC,
        request = request,
        extra = mapOf(KEY_PROXY_LIST to proxies.joinToString("; ").ifBlank { "DIRECT" }),
    )
  }

  @CheckResult
  private fun notFound(): SelfServeResponse =
      SelfServeResponse(
          status = STATUS_NOT_FOUND,
          contentType = TYPE_TEXT,
          body = "Not found. Try / for the TetherFuseNet connection assistant.",
      )

  override fun respond(path: String, request: SelfServeRequest): SelfServeResponse {
    val withoutFragment = path.substringBefore('#')
    val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")

    return when (withoutFragment.substringBefore('?')) {
      "/",
      "/index.html" ->
          fill(
              name = "index.html",
              contentType = TYPE_HTML,
              request = request,
              extra = mapOf(KEY_SOCKS_JS to request.isSocksEnabled.toString()),
          )
      "/tfn.json" -> describe(request)
      "/sing-box.json" -> singBox(request, isTcpOnly = query.split('&').contains("tcp=1"))
      "/proxy.pac" -> proxyAutoConfig(request)
      "/connect.sh" -> fill("connect.sh", TYPE_TEXT, request)
      "/connect.ps1" -> fill("connect.ps1", TYPE_TEXT, request)
      else -> notFound()
    }
  }

  companion object {

    /** Bumped when the documents change in a way an older connect script cannot understand */
    private const val API_VERSION = 1

    private const val RESOURCE_DIRECTORY = "/tfn"
    private const val UNKNOWN_VERSION = "unknown"
    private const val VERSION_PUNCTUATION = ".-_"

    private const val KEY_HOST = "@@HOST@@"
    private const val KEY_PORT = "@@PORT@@"
    private const val KEY_VERSION = "@@VERSION@@"
    private const val KEY_SOCKS_JS = "@@SOCKS_JS@@"
    private const val KEY_PROXY_LIST = "@@PROXY_LIST@@"
    private const val KEY_SOCKS_NETWORK = "@@SOCKS_NETWORK@@"
    private const val KEY_UDP_REJECT_RULE = "@@UDP_REJECT_RULE@@"

    private const val TYPE_HTML = "text/html; charset=utf-8"
    private const val TYPE_JSON = "application/json; charset=utf-8"
    private const val TYPE_PAC = "application/x-ns-proxy-autoconfig"

    // Scripts are served as text so a browser shows them instead of running or saving them
    private const val TYPE_TEXT = "text/plain; charset=utf-8"

    private const val STATUS_OK = 200
    private const val STATUS_NOT_FOUND = 404
    private const val STATUS_CONFLICT = 409
    private const val STATUS_SERVER_ERROR = 500

    private const val SOCKS_DISABLED =
        "SOCKS is turned off in TetherFuseNet. With the Hotspot stopped, turn on the " +
            "SOCKS4A/SOCKS5H Proxy switch under Proxy Mode in the Hotspot tab, start the Hotspot, " +
            "then try again."
  }
}
