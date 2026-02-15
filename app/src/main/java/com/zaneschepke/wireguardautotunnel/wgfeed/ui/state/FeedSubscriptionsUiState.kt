package com.zaneschepke.wireguardautotunnel.wgfeed.ui.state

import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity

data class FeedSubscriptionsUiState(
    val subscriptions: List<FeedSubscriptionEntity> = emptyList(),
    val selectedSubscriptions: List<FeedSubscriptionEntity> = emptyList(),
    val managedTunnelCountBySubscriptionId: Map<Int, Int> = emptyMap(),
)
