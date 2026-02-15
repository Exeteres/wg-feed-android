package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs multiple SSE connections concurrently (one per subscription).
 *
 * We keep per-subscription statuses and allow starting/stopping individual streams.
 */
class WgFeedSseHub(
    private val clientFactory: () -> WgFeedSseClient,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val jobs = LinkedHashMap<Int, Job>()
    private val clients = LinkedHashMap<Int, WgFeedSseClient>()

    private val _statusBySubscriptionId = MutableStateFlow<Map<Int, WgFeedSseStatus>>(emptyMap())
    val statusBySubscriptionId: StateFlow<Map<Int, WgFeedSseStatus>> =
        _statusBySubscriptionId.asStateFlow()

    /**
     * Starts SSE for a subscription using the provided [client] and [url].
     *
     * Returns a job that completes when the stream ends.
     */
    fun start(
        subscriptionId: Int,
        client: WgFeedSseClient,
        url: String,
        onFeedJson: suspend (String) -> Unit,
        onError: suspend (Throwable) -> Unit
    ): Job {
        stop(subscriptionId)

        clients[subscriptionId] = client

        // Forward status updates
        val statusJob =
            scope.launch {
                client.status.collect { st ->
                    _statusBySubscriptionId.value =
                        _statusBySubscriptionId.value.toMutableMap().apply {
                            put(subscriptionId, st)
                        }
                }
            }

        val streamJob =
            scope.launch {
                // This suspends until stopped/cancelled.
                client.start(url, onFeedJson, onError)
            }

        jobs[subscriptionId] =
            scope.launch {
                try {
                    streamJob.join()
                } finally {
                    statusJob.cancel()
                    _statusBySubscriptionId.value =
                        _statusBySubscriptionId.value.toMutableMap().apply {
                            remove(subscriptionId)
                        }
                }
            }

        _statusBySubscriptionId.value = _statusBySubscriptionId.value.toMutableMap().apply {
            put(subscriptionId, WgFeedSseStatus.CONNECTING)
        }

        return jobs.getValue(subscriptionId)
    }

    fun stop(subscriptionId: Int) {
        jobs.remove(subscriptionId)?.cancel()
        clients.remove(subscriptionId)?.stop()
        _statusBySubscriptionId.value = _statusBySubscriptionId.value.toMutableMap().apply {
            remove(subscriptionId)
        }
    }

    fun stopAll() {
        jobs.keys.toList().forEach { stop(it) }
    }

    fun close() {
        stopAll()
        scope.cancel()
    }

    fun newClient(): WgFeedSseClient = clientFactory()
}
