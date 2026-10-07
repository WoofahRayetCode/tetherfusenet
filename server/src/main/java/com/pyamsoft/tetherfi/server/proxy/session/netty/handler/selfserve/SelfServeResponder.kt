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

/** What the proxy knows about the connection that is asking for a document. */
internal data class SelfServeRequest(
    /** The address the client used to reach the proxy, which is the address of this device. */
    val host: String,
    /** The port the client used to reach the proxy. */
    val port: Int,
    /** True if the proxy relays HTTP / HTTPS requests. */
    val isHttpEnabled: Boolean,
    /** True if the proxy relays SOCKS requests (SOCKS5 also covers UDP). */
    val isSocksEnabled: Boolean,
    /** Seconds a connection may sit idle before the proxy closes it, 0 if it never does. */
    val idleTimeoutSeconds: Long,
)

/** A document that is answered by the proxy itself instead of being relayed. */
internal data class SelfServeResponse(
    val status: Int,
    val contentType: String,
    val body: String,
)

/**
 * Documents the proxy answers when a client asks the proxy ITSELF (not a remote server) for them.
 *
 * This is what lets a computer that has not been given any proxy settings, and that is only
 * attached to this device, fetch setup help or connection details from the device it is attached
 * to.
 */
internal fun interface SelfServeResponder {

  /** Answer a request for [path] (query and fragment already allowed, they are ignored) */
  @CheckResult fun respond(path: String, request: SelfServeRequest): SelfServeResponse
}
