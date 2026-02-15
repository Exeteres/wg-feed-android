package com.zaneschepke.wireguardautotunnel.wgfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelManager
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedManagedTunnelDao
import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedSubscriptionDao
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.repository.SelectedSubscriptionsRepository
import com.zaneschepke.wireguardautotunnel.wgfeed.ui.state.FeedSubscriptionsUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WgFeedSubscriptionsViewModel(
    private val subscriptionDao: FeedSubscriptionDao,
    private val selectedSubscriptionsRepository: SelectedSubscriptionsRepository,
    private val tunnelRepository: TunnelRepository,
    private val tunnelManager: TunnelManager,
    managedTunnelDao: FeedManagedTunnelDao,
) : ViewModel() {

    private suspend fun deleteSubscriptionAndManagedTunnels(subscription: FeedSubscriptionEntity) {
        // Stop first (best-effort). Database cleanup is handled via FK cascades.
        val managedTunnels = tunnelRepository.getByFeedSubscriptionId(subscription.id)

        val activeIds = tunnelManager.activeTunnels.value.keys
        managedTunnels
            .filter { activeIds.contains(it.id) }
            .forEach { tunnelManager.stopTunnel(it.id) }

        subscriptionDao.delete(subscription)
    }

    val subscriptionsUiState: StateFlow<FeedSubscriptionsUiState> =
        combine(
            subscriptionDao.getAllFlow(),
            selectedSubscriptionsRepository.flow,
            managedTunnelDao.getAllFlow(),
        ) { subs, selected, mappings ->
            val counts = mutableMapOf<Int, Int>()
            for (m in mappings) {
                counts[m.subscriptionId] = (counts[m.subscriptionId] ?: 0) + 1
            }

            FeedSubscriptionsUiState(
                subscriptions = subs,
                selectedSubscriptions = selected,
                managedTunnelCountBySubscriptionId = counts,
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                FeedSubscriptionsUiState(),
            )

    fun delete(subscription: FeedSubscriptionEntity) {
        viewModelScope.launch { deleteSubscriptionAndManagedTunnels(subscription) }
    }

    fun clearSelectedSubscriptions() {
        selectedSubscriptionsRepository.clear()
    }

    fun toggleSelectedSubscription(subscriptionId: Int) {
        val subs = subscriptionsUiState.value.subscriptions
        val selectedSubs = subscriptionsUiState.value.selectedSubscriptions
        val updated =
            selectedSubs.toMutableList().apply {
                val removed = removeIf { it.id == subscriptionId }
                if (!removed) addAll(subs.filter { it.id == subscriptionId })
            }
        selectedSubscriptionsRepository.set(updated)
    }

    fun toggleSelectAllSubscriptions() {
        val state = subscriptionsUiState.value
        if (state.selectedSubscriptions.size != state.subscriptions.size) {
            selectedSubscriptionsRepository.set(state.subscriptions)
            return
        }
        selectedSubscriptionsRepository.clear()
    }

    fun deleteSelectedSubscriptions() {
        viewModelScope.launch {
            val selected = subscriptionsUiState.value.selectedSubscriptions
            selected.forEach { deleteSubscriptionAndManagedTunnels(it) }
            selectedSubscriptionsRepository.clear()
        }
    }

}
