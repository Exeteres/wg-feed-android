package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SSE client for wg-feed realtime.
 *
 * This class manages a single connection attempt for a given URL. Endpoint rotation/backoff
 * is implemented by the caller per wg-feed spec.
 */
class WgFeedSseClient(
    private val sseClient: HttpClient,
) {
    private val _status = MutableStateFlow(WgFeedSseStatus.DISCONNECTED)
    val status: StateFlow<WgFeedSseStatus> = _status.asStateFlow()

    private val _established = MutableStateFlow(false)
    val established: StateFlow<Boolean> = _established.asStateFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /**
     * Starts the SSE stream.
     *
     * This call suspends until the stream ends, fails, or [stop] is called.
     */
    suspend fun start(
        url: String,
        onFeedJson: suspend (String) -> Unit,
        onError: suspend (Throwable) -> Unit
    ) {
        stop()
        _established.value = false
        val newScope = CoroutineScope(Dispatchers.IO)
        scope = newScope

        val streamUrl = WgFeedEndpoints.stripFragment(url)

        val localJob =
            newScope.launch {
                _status.value = WgFeedSseStatus.CONNECTING
                try {
                    // Disable request timeout for this long-lived SSE connection.
                    sseClient.sse(urlString = streamUrl, request = {
                        timeout { requestTimeoutMillis = Long.MAX_VALUE }
                        headers { append(HttpHeaders.Accept, "text/event-stream") }
                    }) {
                        _status.value = WgFeedSseStatus.CONNECTED
                        _established.value = true
                        incoming.collect { event ->
                            if (!isActive) return@collect
                            handleEvent(event, onFeedJson)
                        }
                    }

                    // If sse() returns normally, treat as disconnected.
                    _status.value = WgFeedSseStatus.DISCONNECTED
                } catch (e: Exception) {
                    _status.value = WgFeedSseStatus.ERROR
                    onError(e)
                    throw e
                }
            }

        job = localJob
        try {
            localJob.join()
        } finally {
            stop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        _status.value = WgFeedSseStatus.DISCONNECTED
    }

    private suspend fun handleEvent(event: ServerSentEvent, onFeedJson: suspend (String) -> Unit) {
        // Spec:
        // - `event: feed` with a single `data` field that contains full wg-feed JSON success response.
        // - ignore `event: ping`.
        val name = event.event
        if (name == "ping") return

        if (name == "feed") {
            val data = event.data
            if (!data.isNullOrBlank()) {
                onFeedJson(data.trim())
            }
        }
    }
}
