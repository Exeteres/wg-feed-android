package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.state.TunnelState
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.SwitchWithDivider
import com.zaneschepke.wireguardautotunnel.ui.common.icon.DataIcon
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import com.zaneschepke.wireguardautotunnel.ui.state.TunnelsUiState
import com.zaneschepke.wireguardautotunnel.util.extensions.asColor
import com.zaneschepke.wireguardautotunnel.util.extensions.openWebUrl
import com.zaneschepke.wireguardautotunnel.viewmodel.SharedAppViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TunnelList(
    uiState: TunnelsUiState,
    modifier: Modifier = Modifier,
    viewModel: SharedAppViewModel,
) {
    val navController = LocalNavController.current
    val context = LocalContext.current

    val lazyListState = rememberLazyListState()

    LazyColumn(
        horizontalAlignment = Alignment.Start,
        modifier =
            modifier
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (uiState.tunnels.isEmpty()) return@detectTapGestures
                        viewModel.clearSelectedTunnels()
                    }
                }
                .overscroll(rememberOverscrollEffect()),
        state = lazyListState,
        userScrollEnabled = true,
        reverseLayout = false,
        flingBehavior = ScrollableDefaults.flingBehavior(),
    ) {
        if (uiState.tunnels.isEmpty()) {
            item {
                GettingStartedLabel(
                    onClick = { context.openWebUrl(it) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        items(uiState.tunnels, key = { it.id }) { tunnel ->
            val tunnelState =
                remember(uiState.activeTunnels) {
                    uiState.activeTunnels[tunnel.id] ?: TunnelState()
                }
            val selected =
                remember(uiState.selectedTunnels) {
                    uiState.selectedTunnels.any { it.id == tunnel.id }
                }
            var leadingIconColor by
                remember(
                    tunnelState.status,
                    tunnelState.logHealthState,
                    tunnelState.pingStates,
                    tunnelState.statistics,
                ) {
                    mutableStateOf(tunnelState.health().asColor())
                }

            val isActiveNow = tunnelState.status.isUpOrStarting()
            val showRestartRequired = isActiveNow && tunnel.isRestartRequired

            val titleText = tunnel.displayTitle ?: tunnel.name
            val iconUrl = tunnel.displayIconUrl

            val subscriptionId = tunnel.feedSubscriptionId
            val subscription = if (subscriptionId != null) uiState.subscriptionsById[subscriptionId] else null
            val manualMode = subscription?.ignoreServerState == true

            val title =
                AnnotatedString(
                    text = titleText,
                )

            val mapping = uiState.managedTunnelsByConfigId[tunnel.id]
            val isForcedLocked = mapping?.isForced == true && !manualMode

            SurfaceRow(
                modifier = Modifier.animateItem(),
                leading = {
                    Box(contentAlignment = Alignment.TopEnd) {
                        if (!iconUrl.isNullOrBlank()) {
                            DataIcon(url = iconUrl, size = 22.dp)
                        } else {
                            Icon(
                                Icons.Rounded.Circle,
                                contentDescription = stringResource(R.string.tunnel_monitoring),
                                tint = leadingIconColor,
                                modifier = Modifier.size(14.dp),
                            )
                        }

                        if (showRestartRequired) {
                            Text(
                                text = "!",
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelSmall,
                                modifier =
                                    Modifier
                                        .padding(0.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.error)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                },
                title = title,
                description =
                    if (showRestartRequired) {
                        {
                            Text(
                                text = stringResource(R.string.tunnel_restart_required_message),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        null
                    },
                onClick = {
                    if (uiState.selectedTunnels.isNotEmpty()) {
                        viewModel.toggleSelectedTunnel(tunnel.id)
                    } else {
                        navController.push(Route.TunnelSettings(tunnel.id))
                        viewModel.clearSelectedTunnels()
                    }
                },
                selected = selected,
                expandedContent =
                    if (!tunnelState.status.isDown()) {
                        {
                            TunnelStatisticsRow(
                                tunnel,
                                tunnelState,
                                uiState.isPingEnabled,
                                uiState.showPingStats,
                            )
                        }
                    } else null,
                onLongClick = { viewModel.toggleSelectedTunnel(tunnel.id) },
                trailing = { modifier ->
                    SwitchWithDivider(
                        checked = isActiveNow,
                        onClick = { checked ->
                            if (isForcedLocked) return@SwitchWithDivider
                            if (checked) viewModel.startTunnel(tunnel)
                            else viewModel.stopTunnel(tunnel)
                        },
                        modifier = modifier,
                        enabled = !isForcedLocked,
                    )
                },
            )
        }
    }
}
