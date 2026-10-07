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

package com.pyamsoft.tetherfi.info.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.info.InfoViewOptionsType
import com.pyamsoft.tetherfi.info.InfoViewState
import com.pyamsoft.tetherfi.info.MutableInfoViewState
import com.pyamsoft.tetherfi.info.R
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.icons.IconPainters
import com.pyamsoft.tetherfi.ui.rememberPortNumber
import com.pyamsoft.tetherfi.ui.rememberServerHostname
import com.pyamsoft.tetherfi.ui.test.TestServerState
import com.pyamsoft.tetherfi.ui.test.makeTestServerState
import org.jetbrains.annotations.TestOnly

private enum class ComputerSetupContentTypes {
  COMPUTER,
}

/**
 * An optional step that comes after the proxy settings: instead of putting proxy settings into the
 * other device, the other device opens a page that this device serves (see SelfServeResponder in
 * the server module) which sets up a tunnel for the whole computer.
 */
internal fun LazyListScope.renderComputerSetup(
    itemModifier: Modifier = Modifier,
    appName: String,
    state: InfoViewState,
    serverViewState: ServerViewState,
    onToggleShowOptions: (InfoViewOptionsType) -> Unit,
) {
  item(
      contentType = ComputerSetupContentTypes.COMPUTER,
  ) {
    OtherInstruction(
        modifier = itemModifier,
    ) {
      Column {
        val showComputerOptions by state.showComputerOptions.collectAsStateWithLifecycle()

        Row(
            modifier = Modifier.clickable { onToggleShowOptions(InfoViewOptionsType.COMPUTER) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              text = stringResource(R.string.view_computer_options),
              style =
                  MaterialTheme.typography.labelLarge.copy(
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  ),
          )

          Icon(
              modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
              painter =
                  if (showComputerOptions) IconPainters.keyboardArrowRight()
                  else IconPainters.keyboardArrowDown(),
              contentDescription = stringResource(R.string.view_computer_options),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        AnimatedVisibility(
            visible = showComputerOptions,
        ) {
          ComputerSetupDetails(
              appName = appName,
              serverViewState = serverViewState,
          )
        }
      }
    }
  }
}

@Composable
private fun ComputerSetupDetails(
    modifier: Modifier = Modifier,
    appName: String,
    serverViewState: ServerViewState,
) {
  Column(
      modifier = modifier.padding(top = MaterialTheme.keylines.baseline),
  ) {
    Text(
        text = stringResource(R.string.computer_explain),
        style =
            MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )

    val isSocksEnabled by serverViewState.isSocksEnabled.collectAsStateWithLifecycle()
    if (!isSocksEnabled) {
      Text(
          modifier = Modifier.padding(top = MaterialTheme.keylines.typography),
          text = stringResource(R.string.computer_socks_off),
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  color = MaterialTheme.colorScheme.error,
              ),
      )
    }

    Text(
        modifier = Modifier.padding(top = MaterialTheme.keylines.baseline),
        text = stringResource(R.string.computer_open_address),
        style =
            MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )

    val connection by serverViewState.connection.collectAsStateWithLifecycle()
    val port by serverViewState.port.collectAsStateWithLifecycle()
    val hostName = rememberServerHostname(connection)
    val portNumber = rememberPortNumber(port)

    val proxyStatus by serverViewState.proxyStatus.collectAsStateWithLifecycle()

    // Only a running hotspot with a usable port has an address worth typing into another device
    val isReady =
        connection is BroadcastNetworkStatus.ConnectionInfo.Connected &&
            proxyStatus is RunningStatus.Running &&
            portNumber.toIntOrNull() != null
    if (isReady) {
      // Selectable so it can be copied
      SelectionContainer {
        Text(
            modifier = Modifier.padding(top = MaterialTheme.keylines.typography),
            text = "http://$hostName:$portNumber/",
            style =
                MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.W700,
                    fontFamily = FontFamily.Monospace,
                ),
        )
      }
    } else {
      Text(
          modifier = Modifier.padding(top = MaterialTheme.keylines.typography),
          text = stringResource(R.string.computer_not_running, appName),
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }

    Text(
        modifier = Modifier.padding(top = MaterialTheme.keylines.baseline),
        text = stringResource(R.string.computer_timeout_hint),
        style =
            MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )

    Text(
        modifier = Modifier.padding(top = MaterialTheme.keylines.typography),
        text = stringResource(R.string.computer_vpn_hint, appName),
        style =
            MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
  }
}

@TestOnly
@Composable
private fun PreviewComputerSetup(
    state: InfoViewState,
    server: TestServerState,
    socks: Boolean,
) {
  LazyColumn {
    renderComputerSetup(
        appName = "TEST",
        state = state,
        serverViewState = makeTestServerState(server, isHttpEnabled = true, isSocksEnabled = socks),
        onToggleShowOptions = {},
    )
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewComputerSetupCollapsed() {
  PreviewComputerSetup(
      state = MutableInfoViewState(),
      server = TestServerState.CONNECTED,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewComputerSetupOpen() {
  PreviewComputerSetup(
      state = MutableInfoViewState().apply { showComputerOptions.value = true },
      server = TestServerState.CONNECTED,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewComputerSetupOpenSocksOff() {
  PreviewComputerSetup(
      state = MutableInfoViewState().apply { showComputerOptions.value = true },
      server = TestServerState.CONNECTED,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewComputerSetupOpenNotRunning() {
  PreviewComputerSetup(
      state = MutableInfoViewState().apply { showComputerOptions.value = true },
      server = TestServerState.EMPTY,
      socks = true,
  )
}
