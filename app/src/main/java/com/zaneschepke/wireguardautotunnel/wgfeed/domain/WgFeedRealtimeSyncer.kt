package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedSubscriptionDao
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedErrorResponse
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedValidation
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import timber.log.Timber

/**
 * Realtime wg-feed syncer for multiple subscriptions.
 *
 * Draft-00 SSE behavior:
 * - connect to one endpoint
 * - if the SSE connection fails/disconnects (retriable), connect to the next endpoint
 */
class WgFeedRealtimeSyncer(
    private val subscriptionDao: FeedSubscriptionDao,
    private val sseHub: WgFeedSseHub,
    private val applyService: WgFeedApplyService,
) {

    /** Per-subscription status, merged and exposed to UI. */
    val statusBySubscriptionId: StateFlow<Map<Int, WgFeedSseStatus>> = sseHub.statusBySubscriptionId

    fun start(subscription: FeedSubscriptionEntity) {
        if (subscription.syncMode != com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSyncMode.REALTIME) return
        if (subscription.isSyncTerminal) return

        Timber.i(
            "wg-feed sse: start subId=${subscription.id} feedId=${subscription.feedId} endpoints=${WgFeedSubscriptionState.endpointsFor(subscription).size}"
        )

        val client = sseHub.newClient()

        // Keep endpoints that caused fallback/disconnects during this runtime session.
        // These are demoted when persisting endpointsJson on new feed events.
        val failedEndpoints = LinkedHashSet<String>()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Parses and persists feed events. Terminal conditions stop SSE for this subscription.
        suspend fun handleFeedEvent(json: String) {
            val now = System.currentTimeMillis()
            val old = subscriptionDao.getById(subscription.id) ?: subscription

            Timber.i("wg-feed sse: event subId=${subscription.id} bytes=${json.length}")

            val parsed =
                applyService.parseSuccessResponse(
                    storedAgeSecretKey = old.ageSecretKey,
                    bodyText = json,
                )

            val data = parsed.getOrElse { e ->
                val msg = e.message ?: "Invalid wg-feed document"

                val terminal = applyService.isTerminalDecryptErrorMessage(msg)
                applyService.persistSyncError(
                    subscription = old,
                    nowMs = now,
                    message = msg,
                    terminal = terminal,
                )

                if (terminal) {
                    stop(subscription.id)
                }

                return
            }

            val applied =
                applyService.applyDocument(
                    old = old,
                    document = data.document,
                    revision = data.revision,
                    ttlSeconds = data.ttlSeconds,
                    supportsSse = data.supportsSse,
                    nowMs = now,
                    failedEndpoints = failedEndpoints,
                    explicit = false,
                )

            if (applied is WgFeedApplyService.ApplyResult.Terminal) {
                stop(subscription.id)
                return
            }
        }

        // Spec-compliant connection loop with the additional policy:
        // - do NOT rotate on disconnect after being connected
        // - rotate only when we fail to establish the SSE connection (initial connect or reconnect)
        scope.launch {
            var currentEndpointIndex = 0

            while (isActive) {
                val latest = subscriptionDao.getById(subscription.id) ?: subscription
                if (latest.syncMode != com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSyncMode.REALTIME || latest.isSyncTerminal) {
                    Timber.i(
                        "wg-feed sse: stop condition met subId=${subscription.id} mode=${latest.syncMode} terminal=${latest.isSyncTerminal}"
                    )
                    stop(subscription.id)
                    return@launch
                }

                val endpoints = WgFeedSubscriptionState.endpointsFor(latest)
                if (endpoints.isEmpty()) {
                    delay(5_000L)
                    continue
                }

                if (currentEndpointIndex >= endpoints.size) currentEndpointIndex = 0
                val endpoint = endpoints[currentEndpointIndex]
                val origin = WgFeedLog.endpointOrigin(endpoint)

                Timber.i("wg-feed sse: connect subId=${subscription.id} endpoint=$origin")

                try {
                    val streamJob = sseHub.start(
                        latest.id,
                        client,
                        endpoint,
                        onFeedJson = { json ->
                            handleFeedEvent(json)
                        },
                        onError = { e ->
                            val msg = e.message ?: "SSE error"
                            Timber.w(
                                "wg-feed sse: onError subId=${latest.id} endpoint=$origin msg=$msg"
                            )
                            applyService.persistSyncError(
                                subscription = latest,
                                nowMs = System.currentTimeMillis(),
                                message = msg,
                                terminal = false,
                            )
                        },
                    )
                    streamJob.join()
                } catch (e: Exception) {
                    val now = System.currentTimeMillis()
                    val msg = e.message ?: e::class.simpleName ?: "SSE error"
                    Timber.w(
                        "wg-feed sse: exception subId=${latest.id} endpoint=$origin msg=$msg"
                    )

                    // Persist textual error for UI.
                    applyService.persistSyncError(
                        subscription = latest,
                        nowMs = now,
                        message = msg,
                        terminal = false,
                    )

                    // Connect-phase wg-feed envelope error parity.
                    val parsedErr = parseErrorResponseOrNull(e)
                    if (parsedErr != null && !parsedErr.retriable) {
                        applyService.persistSyncError(
                            subscription = latest,
                            nowMs = now,
                            message = parsedErr.message,
                            terminal = true,
                        )
                        stop(subscription.id)
                        return@launch
                    }
                }

                val established = client.established.value
                Timber.i(
                    "wg-feed sse: disconnect subId=${latest.id} endpoint=$origin established=$established"
                )

                if (established) {
                    // Successful SSE connection (even if no feed events arrive): clear lastError.
                    val refreshed = subscriptionDao.getById(subscription.id) ?: latest
                    if (refreshed.lastError != null) {
                        subscriptionDao.update(refreshed.copy(lastError = null))
                    }
                }

                if (!established) {
                    failedEndpoints.add(endpoint)
                    persistEndpointsDemotionIfNeeded(subscription.id, failedEndpoints)
                    currentEndpointIndex = (currentEndpointIndex + 1) % endpoints.size
                }

                delay(1_000L)
            }
        }
    }

    fun stop(subscriptionId: Int) {
        Timber.i("wg-feed sse: stop subId=$subscriptionId")
        sseHub.stop(subscriptionId)
    }

    fun stopAll() {
        Timber.i("wg-feed sse: stopAll")
        sseHub.stopAll()
    }

    private data class ParsedError(val message: String, val retriable: Boolean)

    private suspend fun parseErrorResponseOrNull(t: Throwable): ParsedError? {
        val cre = t as? ClientRequestException ?: return null
        val bodyText = runCatching { cre.response.bodyAsText() }.getOrNull() ?: return null

        val err =
            try {
                WgFeedJson.json.decodeFromString(WgFeedErrorResponse.serializer(), bodyText)
            } catch (_: SerializationException) {
                return null
            }

        return try {
            WgFeedValidation.validateErrorResponse(err)
            ParsedError(err.message, err.retriable)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private suspend fun persistEndpointsDemotionIfNeeded(
        subscriptionId: Int,
        failedEndpoints: Set<String>,
    ) {
        if (failedEndpoints.isEmpty()) return
        val refreshed = subscriptionDao.getById(subscriptionId) ?: return
        val current = WgFeedSubscriptionState.endpointsFor(refreshed)
        val reordered = WgFeedSubscriptionState.demoteFailedEndpoints(current, failedEndpoints)
        if (reordered != current) {
            subscriptionDao.update(
                refreshed.copy(
                    endpointsJson = WgFeedSubscriptionState.encodeEndpoints(reordered),
                ),
            )
        }
    }
}
