package com.zaneschepke.wireguardautotunnel.wgfeed.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.dialog.InfoDialog
import com.zaneschepke.wireguardautotunnel.ui.common.icon.DataIcon
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import com.zaneschepke.wireguardautotunnel.ui.sideeffect.LocalSideEffect
import com.zaneschepke.wireguardautotunnel.viewmodel.SharedAppViewModel
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.ui.WgFeedSubscriptionAddViewModel
import com.zaneschepke.wireguardautotunnel.wgfeed.ui.WgFeedSubscriptionsViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.viewmodel.koinActivityViewModel
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun WgFeedSubscriptionsScreen(
    viewModel: WgFeedSubscriptionsViewModel = koinViewModel(),
    sharedViewModel: SharedAppViewModel = koinActivityViewModel(),
) {
    val navController = LocalNavController.current
    val uiState by viewModel.subscriptionsUiState.collectAsStateWithLifecycle()

    val showAddDialogState = rememberSaveable { mutableStateOf(false) }
    val showDeleteDialogState = rememberSaveable { mutableStateOf(false) }

    sharedViewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is LocalSideEffect.Modal.FeedAddSubscription -> showAddDialogState.value = true
            is LocalSideEffect.Modal.FeedSelectAllSubscriptions -> viewModel.toggleSelectAllSubscriptions()
            is LocalSideEffect.Modal.FeedDeleteSubscriptions -> showDeleteDialogState.value = true
            else -> Unit
        }
    }

    if (showAddDialogState.value) {
        WgFeedSubscriptionAddDialog(
            onDismiss = { showAddDialogState.value = false },
            onCreated = { id ->
                showAddDialogState.value = false
                navController.push(Route.FeedSubscriptionDetails(id))
            },
        )
    }

    if (showDeleteDialogState.value) {
        InfoDialog(
            onDismiss = { showDeleteDialogState.value = false },
            onAttest = {
                viewModel.deleteSelectedSubscriptions()
                showDeleteDialogState.value = false
            },
            title = stringResource(R.string.delete),
            body = { Text(text = stringResource(R.string.delete_tunnel_message)) },
            confirmText = stringResource(R.string.yes),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            viewModel.clearSelectedSubscriptions()
                        }
                    }
        ) {
            items(uiState.subscriptions, key = { it.id }) { sub ->
                val selected = uiState.selectedSubscriptions.any { it.id == sub.id }
                val managedCount = uiState.managedTunnelCountBySubscriptionId[sub.id] ?: 0
                SubscriptionRow(
                    subscription = sub,
                    selected = selected,
                    managedTunnelCount = managedCount,
                    onClick = {
                        if (uiState.selectedSubscriptions.isNotEmpty()) {
                            viewModel.toggleSelectedSubscription(sub.id)
                        } else {
                            navController.push(Route.FeedSubscriptionDetails(sub.id))
                            viewModel.clearSelectedSubscriptions()
                        }
                    },
                    onLongClick = { viewModel.toggleSelectedSubscription(sub.id) },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionRow(
    subscription: FeedSubscriptionEntity,
    selected: Boolean,
    managedTunnelCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val subtitle = stringResource(R.string.wg_feed_managing_tunnels_count, managedTunnelCount)

    val hasProblem =
        !subscription.warningMessage.isNullOrBlank() ||
            subscription.isSyncTerminal ||
            !subscription.lastError.isNullOrBlank()

    SurfaceRow(
        title = AnnotatedString(subscription.displayTitle),
        leading = {
            Box(contentAlignment = Alignment.TopEnd) {
                subscription.displayIconUrl?.takeIf { it.isNotBlank() }?.let { iconUrl ->
                    DataIcon(iconUrl)
                }
                if (hasProblem) {
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
        onClick = { onClick() },
        onLongClick = { onLongClick() },
        selected = selected,
        description = { Text(text = subtitle, style = MaterialTheme.typography.bodySmall) },
    )
}

@Composable
fun WgFeedSubscriptionAddDialog(
    onDismiss: () -> Unit,
    onCreated: (Int) -> Unit,
    viewModel: WgFeedSubscriptionAddViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(state.createdSubscriptionId) {
        state.createdSubscriptionId?.let(onCreated)
    }

    InfoDialog(
        onDismiss = onDismiss,
        title = androidx.compose.ui.res.stringResource(R.string.add_subscription),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfigurationTextBox(
                    value = state.url,
                    label = androidx.compose.ui.res.stringResource(R.string.subscription_url),
                    hint = "https://",
                    onValueChange = viewModel::setUrl,
                    isError = state.error != null,
                )

                state.error?.let { err ->
                    val message =
                        if (err == "duplicate") {
                            androidx.compose.ui.res.stringResource(R.string.duplicate_subscription)
                        } else {
                            err
                        }
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmText = androidx.compose.ui.res.stringResource(R.string.okay),
        onAttest = { viewModel.addSubscription() },
    )
}
