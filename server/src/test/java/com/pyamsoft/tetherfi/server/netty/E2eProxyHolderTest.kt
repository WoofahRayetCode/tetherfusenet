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

import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Not a test of its own: it runs the real proxy until a file appears, so that testing/e2e/run.sh
 * can point a real tunnel at it. Skipped unless TFN_E2E_HOST is set.
 */
class E2eProxyHolderTest {

  @Test
  fun `hold the proxy open for testing e2e run sh`() {
    // Checked out here and not inside the coroutine, which would print a stack trace for a skip
    val host = System.getenv("TFN_E2E_HOST")
    assumeTrue("Only runs for testing/e2e/run.sh", host != null)

    val port = requireNotNull(System.getenv("TFN_E2E_PORT")).toInt()
    val stop = File(requireNotNull(System.getenv("TFN_E2E_STOP")))

    runBlockingWithDelays(timeout = HOLD_LIMIT_SECONDS.seconds) {
      TestSetup.withNetty(
          hostName = requireNotNull(host),
          port = port,
          // TODO(Peter): Do we need test dispatchers?
          dispatchers = AppDispatchers.create(),
          isLoggingEnabled = true,
      ) {
        while (!stop.exists()) {
          delay(POLL_MILLIS.milliseconds)
        }
      }
    }
  }

  private companion object {
    const val HOLD_LIMIT_SECONDS = 900
    const val POLL_MILLIS = 200L
  }
}
