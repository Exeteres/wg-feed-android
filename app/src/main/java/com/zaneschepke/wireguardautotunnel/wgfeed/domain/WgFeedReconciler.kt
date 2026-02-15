package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelManager
import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedManagedTunnelDao
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedManagedTunnelEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedDocument
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedTunnel
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedValidation

class WgFeedReconciler(
    private val tunnelRepository: TunnelRepository,
    private val managedTunnelDao: FeedManagedTunnelDao,
    private val tunnelManager: TunnelManager,
) {

    data class ReconcileResult(
        val created: Int,
        val updated: Int,
        val deleted: Int,
        val enforcedSwitchTunnelId: Int? = null,
    )

    /**
     * Apply the passed wg-feed document onto local tunnel configs.
     */
    suspend fun reconcile(
        subscription: FeedSubscriptionEntity,
        doc: WgFeedDocument
    ): ReconcileResult {
        WgFeedValidation.validateDocument(doc)

        val existingMappings = managedTunnelDao.getBySubscription(subscription.id)
        val mappingByFeedTunnelId = existingMappings.associateBy { it.feedTunnelId }

        val existingTunnels = tunnelRepository.getAll()
        val existingNames = existingTunnels.map { it.name }

        var created = 0
        var updated = 0
        var deleted = 0

        val currentFeedIds = doc.tunnels.map { it.id }.toSet()

        // Create/update in feed order
        for (feedTunnel in doc.tunnels) {
            val mapping = mappingByFeedTunnelId[feedTunnel.id]
            if (mapping == null) {
                createNewTunnel(subscription, feedTunnel, existingNames)
                created++
            } else {
                val didUpdate = updateExistingTunnel(subscription, feedTunnel, mapping)
                if (didUpdate) updated++
            }
        }

        // Delete
        // Policy: don't delete a tunnel that is currently enabled/active locally.
        // Keep its mapping so a later reconcile can delete it when it becomes possible.
        val toDelete = existingMappings.filter { it.feedTunnelId !in currentFeedIds }
        for (m in toDelete) {
            val local = tunnelRepository.getById(m.tunnelConfigId)

            // "Enabled" meaning: either configured active in DB or currently running.
            // If either is true, defer deletion.
            val isEnabledOrActive =
                (local?.isActive == true) || tunnelManager.activeTunnels.value.containsKey(m.tunnelConfigId)

            if (isEnabledOrActive) {
                continue
            }

            if (local != null) {
                tunnelRepository.delete(local)
            }
            managedTunnelDao.deleteByFeedTunnelIds(subscription.id, listOf(m.feedTunnelId))
            deleted++
        }

        // Enforce forced/enabled state (best-effort) and auto-activation rule
        val enforcedTargetTunnelId =
            if (!subscription.ignoreServerState) enforceDesiredState(subscription, doc) else null

        return ReconcileResult(created, updated, deleted, enforcedTargetTunnelId)
    }

    private suspend fun createNewTunnel(
        subscription: FeedSubscriptionEntity,
        feedTunnel: WgFeedTunnel,
        existingNames: List<String>,
    ) {
        val baseName = feedTunnel.name
        val uniqueName = makeUniqueByDashSuffix(baseName, existingNames)

        val newConfig =
            TunnelConfig(
                name = uniqueName,
                wgQuick = feedTunnel.wgQuickConfig,
                amQuick = "",
                // Spec: when not forced, `enabled` is only a default for newly created tunnels.
                // In manual mode, ignore server state entirely.
                isActive = if (!subscription.ignoreServerState && !feedTunnel.forced) feedTunnel.enabled else false,
                displayTitle = feedTunnel.displayInfo.title.takeIf { it.isNotBlank() },
                displayDescription = feedTunnel.displayInfo.description?.takeIf { it.isNotBlank() },
                displayIconUrl = feedTunnel.displayInfo.iconUrl,
                feedSubscriptionId = subscription.id,
                isReadOnly = true,
            )

        tunnelRepository.save(newConfig)

        val saved = tunnelRepository.findByTunnelName(uniqueName) ?: return
        val tunnelId = saved.id

        managedTunnelDao.upsert(
            FeedManagedTunnelEntity(
                subscriptionId = subscription.id,
                feedTunnelId = feedTunnel.id,
                tunnelConfigId = tunnelId,
                isForced = feedTunnel.forced,
            ),
        )
    }

    private fun makeUniqueByDashSuffix(baseName: String, existingNames: List<String>): String {
        // Spec suggests -1, -2 suffixing when uniqueness is required.
        val used = existingNames.toHashSet()
        if (!used.contains(baseName)) return baseName

        var counter = 1
        while (true) {
            val candidate = "$baseName-$counter"
            if (!used.contains(candidate)) return candidate
            counter++
        }
    }

    private suspend fun updateExistingTunnel(
        subscription: FeedSubscriptionEntity,
        feedTunnel: WgFeedTunnel,
        mapping: FeedManagedTunnelEntity,
    ): Boolean {
        val local = tunnelRepository.getById(mapping.tunnelConfigId)
        if (local == null) {
            // Drift: recreate and update mapping
            createNewTunnel(subscription, feedTunnel, tunnelRepository.getAll().map { it.name })
            return true
        }

        var didUpdate = false
        var updatedLocal = local

        var didConfigPayloadChange = false

        if (local.wgQuick != feedTunnel.wgQuickConfig || local.amQuick.isNotBlank()) {
            updatedLocal = updatedLocal.copy(wgQuick = feedTunnel.wgQuickConfig, amQuick = "")
            didUpdate = true
            didConfigPayloadChange = true
        }

        // Always keep wg-feed managed tunnels read-only + linked to subscription
        val desiredTitle = feedTunnel.displayInfo.title.takeIf { it.isNotBlank() }
        val desiredDesc = feedTunnel.displayInfo.description?.takeIf { it.isNotBlank() }
        val desiredIcon = feedTunnel.displayInfo.iconUrl

        if (
            updatedLocal.displayTitle != desiredTitle ||
            updatedLocal.displayDescription != desiredDesc ||
            updatedLocal.displayIconUrl != desiredIcon ||
            updatedLocal.feedSubscriptionId != subscription.id ||
            !updatedLocal.isReadOnly
        ) {
            updatedLocal =
                updatedLocal.copy(
                    displayTitle = desiredTitle,
                    displayDescription = desiredDesc,
                    displayIconUrl = desiredIcon,
                    feedSubscriptionId = subscription.id,
                    isReadOnly = true,
                )
            didUpdate = true
        }

        val isActiveNow = tunnelManager.activeTunnels.value.containsKey(mapping.tunnelConfigId)

        val isRestartRequired =
            if (didConfigPayloadChange && isActiveNow) {
                true
            } else {
                updatedLocal.isRestartRequired
            }

        if (updatedLocal.isRestartRequired != isRestartRequired) {
            updatedLocal = updatedLocal.copy(isRestartRequired = isRestartRequired)
            didUpdate = true
        }

        if (didUpdate) {
            tunnelRepository.save(updatedLocal)
        }

        managedTunnelDao.upsert(mapping.copy(isForced = feedTunnel.forced))

        return didUpdate
    }

    private suspend fun enforceDesiredState(
        subscription: FeedSubscriptionEntity,
        doc: WgFeedDocument
    ): Int? {
        val mappings =
            managedTunnelDao.getBySubscription(subscription.id).associateBy { it.feedTunnelId }

        // 1) Enforce forced=true tunnels state equals enabled.
        //    (Presence is always supported in this app, so we don't need "skip create" for enabled=false.)
        val activeIds = tunnelManager.activeTunnels.value.keys

        for (t in doc.tunnels) {
            if (!t.forced) continue
            val localId = mappings[t.id]?.tunnelConfigId ?: continue
            val isActive = activeIds.contains(localId)

            if (t.enabled) {
                // Don't start here; auto-activation logic below decides what to start for mobile.
                // But if the tunnel is already active, keep it.
                continue
            }

            // forced=true + enabled=false => must be down
            if (isActive) {
                tunnelManager.stopTunnel(localId)
            }
        }

        // 2) Mobile guidance: pick first forced=true && enabled=true and activate it.
        val targetFeedTunnel = doc.tunnels.firstOrNull { it.forced && it.enabled }
        val targetTunnelConfigId = targetFeedTunnel?.let { mappings[it.id]?.tunnelConfigId }

        if (targetTunnelConfigId != null) {
            // Best-effort: stop other forced+enabled tunnels that are active (single-active behavior).
            // We intentionally don't stop non-forced tunnels to avoid disrupting user state.
            val forcedEnabledLocalIds =
                doc.tunnels
                    .filter { it.forced && it.enabled }
                    .mapNotNull { ft -> mappings[ft.id]?.tunnelConfigId }
                    .toSet()

            for (id in activeIds) {
                if (id == targetTunnelConfigId) continue
                if (forcedEnabledLocalIds.contains(id)) {
                    tunnelManager.stopTunnel(id)
                }
            }

            if (!activeIds.contains(targetTunnelConfigId)) {
                tunnelRepository.getById(targetTunnelConfigId)
                    ?.let { tunnelManager.startTunnel(it) }
            }
        }

        return targetTunnelConfigId
    }
}
