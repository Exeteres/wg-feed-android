package com.zaneschepke.wireguardautotunnel.wgfeed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.icon.DataIcon
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSyncMode
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedSseStatus
import com.zaneschepke.wireguardautotunnel.wgfeed.ui.WgFeedSubscriptionDetailsViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WgFeedSubscriptionDetailsScreen(subscriptionId: Int) {
    val viewModel: WgFeedSubscriptionDetailsViewModel =
        koinViewModel(parameters = { parametersOf(subscriptionId) })

    val state by viewModel.state.collectAsStateWithLifecycle()
    val sub = state.subscription

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        if (sub == null) {
            Text(text = stringResource(R.string.subscription_not_found))
            return
        }

        val problemMessage =
            when {
                !sub.warningMessage.isNullOrBlank() -> sub.warningMessage
                sub.isSyncTerminal -> sub.lastError ?: stringResource(R.string.sync_disabled)
                !sub.lastError.isNullOrBlank() -> sub.lastError
                else -> null
            }

        problemMessage?.let { msg ->
            Column {
                GroupLabel(
                    stringResource(R.string.problem),
                    modifier = Modifier,
                )
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sub.displayIconUrl?.takeIf { it.isNotBlank() }?.let { iconUrl ->
                DataIcon(
                    url = iconUrl,
                    size = 40.dp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                )
                Spacer(modifier = Modifier.size(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sub.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = stringResource(R.string.wg_feed_managing_tunnels_count, state.mappings.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        sub.displayDescription?.takeIf { it.isNotBlank() }?.let { desc ->
            Column {
                GroupLabel(
                    stringResource(R.string.subscription_description),
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }
        }

        GroupLabel(
            stringResource(R.string.settings),
            modifier = Modifier.padding(top = 12.dp),
        )

        SettingRow(
            title = stringResource(R.string.ignore_server_state),
            subtitle =
                if (sub.ignoreServerState) stringResource(R.string.ignore_server_state_desc_on)
                else stringResource(R.string.ignore_server_state_desc_off),
            checked = sub.ignoreServerState,
            onCheckedChange = viewModel::setIgnoreServerState,
        )

        GroupLabel(
            stringResource(R.string.sync_mode),
            modifier = Modifier.padding(top = 12.dp),
        )

        val realtimeAllowed = sub.supportsSse
        val expandedState = remember { mutableStateOf(false) }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        when (sub.syncMode) {
                            FeedSyncMode.MANUAL -> stringResource(R.string.sync_mode_manual)
                            FeedSyncMode.POLLING -> stringResource(R.string.sync_mode_polling)
                            FeedSyncMode.REALTIME -> stringResource(R.string.sync_mode_realtime)
                        },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text =
                        when (sub.syncMode) {
                            FeedSyncMode.MANUAL -> stringResource(R.string.sync_mode_manual_desc)
                            FeedSyncMode.POLLING -> stringResource(R.string.sync_mode_polling_desc)
                            FeedSyncMode.REALTIME ->
                                if (!realtimeAllowed)
                                    stringResource(R.string.sync_mode_realtime_unsupported_desc)
                                else stringResource(R.string.sync_mode_realtime_desc)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Box {
                OutlinedButton(onClick = { expandedState.value = true }) {
                    Text(text = stringResource(R.string.change))
                }

                DropdownMenu(
                    expanded = expandedState.value,
                    onDismissRequest = { expandedState.value = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sync_mode_manual)) },
                        onClick = {
                            expandedState.value = false
                            viewModel.setSyncMode(FeedSyncMode.MANUAL)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sync_mode_polling)) },
                        onClick = {
                            expandedState.value = false
                            viewModel.setSyncMode(FeedSyncMode.POLLING)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sync_mode_realtime)) },
                        enabled = realtimeAllowed,
                        onClick = {
                            expandedState.value = false
                            viewModel.setSyncMode(FeedSyncMode.REALTIME)
                        },
                    )
                }
            }
        }

        if (sub.syncMode == FeedSyncMode.POLLING) {
            SyncSubBlock(
                title = stringResource(R.string.sync_interval),
                value = formatApproxInterval(state.pollingIntervalSeconds ?: 0L),
            )
            SyncSubBlock(
                title = stringResource(R.string.next_sync),
                value = formatApproxInterval(state.pollingEtaSeconds ?: 0L),
            )
            SyncSubBlock(
                title = stringResource(R.string.last_checked),
                value = formatTimestamp(sub.lastCheckedAtMs),
            )
        }

        if (sub.syncMode == FeedSyncMode.REALTIME) {
            val statusText =
                when (state.sseStatus) {
                    WgFeedSseStatus.CONNECTING -> stringResource(R.string.sse_connecting)
                    WgFeedSseStatus.CONNECTED -> stringResource(R.string.sse_connected)
                    WgFeedSseStatus.DISCONNECTED -> stringResource(R.string.sse_disconnected)
                    WgFeedSseStatus.ERROR -> stringResource(R.string.sse_disconnected)
                }

            SyncSubBlock(
                title = stringResource(R.string.sse_status),
                value = statusText,
                valueIsError = state.sseStatus == WgFeedSseStatus.ERROR,
            )

            SyncSubBlock(
                title = stringResource(R.string.last_checked),
                value = formatTimestamp(sub.lastCheckedAtMs),
            )
        }

        SyncSubBlock(
            title = stringResource(R.string.last_synced),
            value = formatTimestamp(sub.lastSyncedAtMs),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = viewModel::syncNow, enabled = !sub.isSyncTerminal) {
                Text(text = stringResource(R.string.sync_now))
            }
        }
    }
}

@Composable
private fun SyncSubBlock(
    title: String,
    value: String,
    valueIsError: Boolean = false,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (valueIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatTimestamp(ms: Long?): String {
    if (ms == null || ms <= 0L) return "—"

    return try {
        val df = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
            java.util.Locale.getDefault(),
        )
        df.format(java.util.Date(ms))
    } catch (_: Exception) {
        ms.toString()
    }
}

@Composable
private fun formatApproxInterval(seconds: Long): String {
    if (seconds <= 0L) return "—"
    val minutes = kotlin.math.ceil(seconds / 60.0).toLong()
    return if (minutes < 60) {
        stringResource(R.string.approx_minutes, minutes)
    } else {
        val hours = kotlin.math.ceil(minutes / 60.0).toLong()
        stringResource(R.string.approx_hours, hours)
    }
}
