package com.zaneschepke.wireguardautotunnel.wgfeed.domain.repository

import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SelectedSubscriptionsRepository {
    private val _selectedSubscriptionsFlow = MutableStateFlow<List<FeedSubscriptionEntity>>(emptyList())
    val flow = _selectedSubscriptionsFlow.asStateFlow()

    fun clear() {
        _selectedSubscriptionsFlow.update { emptyList() }
    }

    fun set(subscriptions: List<FeedSubscriptionEntity>) {
        _selectedSubscriptionsFlow.update { subscriptions }
    }
}
