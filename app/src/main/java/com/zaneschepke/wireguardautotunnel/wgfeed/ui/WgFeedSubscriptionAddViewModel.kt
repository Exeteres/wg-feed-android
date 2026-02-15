package com.zaneschepke.wireguardautotunnel.wgfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zaneschepke.wireguardautotunnel.BuildConfig
import com.zaneschepke.wireguardautotunnel.WireGuardAutoTunnel
import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedSubscriptionDao
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSyncMode
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedAge
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedReconciler
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedSubscriptionState
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedSyncer
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedValidation
import com.zaneschepke.wireguardautotunnel.wgfeed.worker.WgFeedPollingScheduler
import com.zaneschepke.wireguardautotunnel.wgfeed.worker.WgFeedRealtimeWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WgFeedSubscriptionAddViewModel(
    private val subscriptionDao: FeedSubscriptionDao,
    private val syncer: WgFeedSyncer,
    private val reconciler: WgFeedReconciler,
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val isSaving: Boolean = false,
        val error: String? = null,
        val createdSubscriptionId: Int? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun setUrl(url: String) {
        _state.update { it.copy(url = url, error = null) }
    }

    fun addSubscription() {
        val url = state.value.url.trim()
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                if (BuildConfig.DEBUG) {
                    WgFeedValidation.requireHttpOrHttpsUrl(url)
                } else {
                    WgFeedValidation.requireHttpsUrl(url)
                }

                // Pre-flight fetch/validate WITHOUT inserting a temporary DB row.
                val preview = syncer.previewNewSubscription(url)
                val data = preview.getOrElse { e ->
                    _state.update { it.copy(isSaving = false, error = e.message ?: "Sync failed") }
                    return@launch
                }

                // enforce duplicate feed.id rule: if another subscription already exists, block.
                val existingByFeed = subscriptionDao.getByFeedId(data.document.id)
                if (existingByFeed != null) {
                    _state.update { it.copy(isSaving = false, error = "duplicate") }
                    return@launch
                }

                // Persist age key material from Setup URL fragment (if any)
                val ageSecretKey = WgFeedAge.secretKeyFromSetupUrl(url)

                val entity =
                    FeedSubscriptionEntity(
                        feedId = data.document.id,
                        // Persist endpoints[] for ongoing sync (Setup URL is not stored).
                        endpointsJson = WgFeedSubscriptionState.encodeEndpoints(data.document.endpoints),
                        // Persist age key material from Setup URL fragment (if any)
                        ageSecretKey = ageSecretKey,
                        ignoreServerState = false,
                        syncMode = FeedSyncMode.POLLING,
                        lastCheckedAtMs = System.currentTimeMillis(),
                        lastSyncedAtMs = System.currentTimeMillis(),
                        // `lastKnownRevision` tracks the revision we fetched+validated and attempted to apply.
                        lastKnownRevision = data.revision,
                        ttlSeconds = data.ttlSeconds,
                        supportsSse = data.supportsSse,
                        displayTitle = data.document.displayInfo.title,
                        displayDescription = data.document.displayInfo.description,
                        displayIconUrl = data.document.displayInfo.iconUrl,
                        warningMessage = data.document.warningMessage,
                        lastError = null,
                        isSyncTerminal = false,
                    )

                // Insert once with the real feedId + fetched metadata.
                val newId = subscriptionDao.insert(entity).toInt()
                val sub = subscriptionDao.getById(newId) ?: error("Failed to create subscription")

                // Reconcile immediately so user sees tunnels.
                // Reuse shared implementation (still explicit=false here because we already have the doc);
                // keep the direct reconcile to avoid extra network call.
                reconciler.reconcile(sub, data.document)

                // Start background schedule for this subscription.
                if (sub.syncMode == FeedSyncMode.POLLING) {
                    WgFeedPollingScheduler.schedule(WireGuardAutoTunnel.instance, sub)
                } else if (sub.syncMode == FeedSyncMode.REALTIME && !sub.isSyncTerminal) {
                    WgFeedRealtimeWorker.start(WireGuardAutoTunnel.instance)
                }

                _state.update { it.copy(isSaving = false, createdSubscriptionId = sub.id) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isSaving = false, error = e.message ?: "Unknown error")
                }
            }
        }
    }
}