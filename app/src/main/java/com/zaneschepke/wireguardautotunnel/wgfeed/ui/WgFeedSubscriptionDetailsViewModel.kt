package com.zaneschepke.wireguardautotunnel.wgfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zaneschepke.wireguardautotunnel.WireGuardAutoTunnel
import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedManagedTunnelDao
import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedSubscriptionDao
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedManagedTunnelEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSyncMode
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedRealtimeSyncer
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedSseStatus
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedSyncer
import com.zaneschepke.wireguardautotunnel.wgfeed.worker.WgFeedPollingScheduler
import com.zaneschepke.wireguardautotunnel.wgfeed.worker.WgFeedRealtimeWorker
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WgFeedSubscriptionDetailsViewModel(
    private val subscriptionId: Int,
    private val subscriptionDao: FeedSubscriptionDao,
    managedTunnelDao: FeedManagedTunnelDao,
    private val syncer: WgFeedSyncer,
    realtimeSyncer: WgFeedRealtimeSyncer,
) : ViewModel() {

    private val feedManagedTunnelDao = managedTunnelDao

    data class UiState(
        val subscription: FeedSubscriptionEntity? = null,
        val mappings: List<FeedManagedTunnelEntity> = emptyList(),
        val pollingIntervalSeconds: Long? = null,
        val pollingEtaSeconds: Long? = null,
        val sseStatus: WgFeedSseStatus = WgFeedSseStatus.DISCONNECTED,
    )

    val state: StateFlow<UiState> =
        combine(
            subscriptionDao.getAllFlow(),
            feedManagedTunnelDao.getAllFlow(),
            realtimeSyncer.statusBySubscriptionId,
        ) { subs, mappings, sseById ->
            val sub = subs.firstOrNull { it.id == subscriptionId }
            val map =
                if (sub == null) emptyList() else mappings.filter { it.subscriptionId == subscriptionId }

            val intervalSec =
                if (sub != null && sub.syncMode == FeedSyncMode.POLLING) {
                    sub.ttlSeconds.toLong().coerceAtLeast(0L)
                } else null

            val etaSec =
                if (sub != null && intervalSec != null) {
                    val now = System.currentTimeMillis()
                    val last = sub.lastCheckedAtMs
                    if (last <= 0L) 0L
                    else {
                        val nextAt = last + intervalSec * 1000L
                        ((nextAt - now) / 1000L).coerceAtLeast(0L)
                    }
                } else null

            val sseStatus =
                if (sub != null) {
                    sseById[sub.id] ?: WgFeedSseStatus.DISCONNECTED
                } else {
                    WgFeedSseStatus.DISCONNECTED
                }

            UiState(
                subscription = sub,
                mappings = map,
                pollingIntervalSeconds = intervalSec,
                pollingEtaSeconds = etaSec,
                sseStatus = sseStatus,
            )
        }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L),
                UiState()
            )

    fun setIgnoreServerState(ignore: Boolean) {
        val sub = state.value.subscription ?: return
        viewModelScope.launch { subscriptionDao.update(sub.copy(ignoreServerState = ignore)) }
    }

    fun setSyncMode(mode: FeedSyncMode) {
        val sub = state.value.subscription ?: return
        viewModelScope.launch {
            val updated = sub.copy(syncMode = mode)
            subscriptionDao.update(updated)

            if (mode == FeedSyncMode.POLLING) {
                WgFeedPollingScheduler.schedule(WireGuardAutoTunnel.instance, updated)
            }

            if (mode == FeedSyncMode.REALTIME) {
                WgFeedRealtimeWorker.start(WireGuardAutoTunnel.instance)
            }
        }
    }

    fun syncNow() {
        val sub = state.value.subscription ?: return
        viewModelScope.launch {
            // Always allowed, even for MANUAL mode.
            syncer.syncOnce(sub, explicit = true)
        }
    }
}
