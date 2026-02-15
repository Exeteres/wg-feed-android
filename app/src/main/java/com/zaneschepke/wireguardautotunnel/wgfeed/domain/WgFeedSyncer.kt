package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import com.zaneschepke.wireguardautotunnel.BuildConfig
import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedSubscriptionDao
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedDocument
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedErrorResponse
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedValidation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import timber.log.Timber

class WgFeedSyncer(
    private val httpClient: HttpClient,
    private val subscriptionDao: FeedSubscriptionDao,
    private val applyService: WgFeedApplyService,
) {

    sealed class SyncResult {
        data class Success(
            val subscription: FeedSubscriptionEntity,
            val changed: Boolean,
            /** Present only when caller should reconcile (revision-change or explicit). */
            val data: SyncData?,
        ) : SyncResult()

        data class Disabled(val subscription: FeedSubscriptionEntity) : SyncResult()

        data class Error(val subscription: FeedSubscriptionEntity, val message: String) :
            SyncResult()

        data class Terminal(val subscription: FeedSubscriptionEntity, val message: String) :
            SyncResult()
    }

    /** Parsed + validated wg-feed response data (includes the parsed document). */
    data class SyncData(
        val nowMs: Long,
        val httpStatus: Int,
        val document: WgFeedDocument,
        val revision: String,
        val ttlSeconds: Int,
        val supportsSse: Boolean,
        val warningMessage: String?,
    )

    /** Fetch/validate wg-feed for a new URL without touching the database. */
    suspend fun previewNewSubscription(url: String): Result<SyncData> {
        val normalizedUrl = url.trim()
        validateUrl(normalizedUrl)

        val response = httpGet(normalizedUrl, lastKnownRevision = null)
        if (response.status != HttpStatusCode.OK) {
            return Result.failure(IllegalArgumentException(readErrorMessage(response)))
        }

        val parsed =
            applyService.parseSuccessResponse(
                storedAgeSecretKey = WgFeedAge.secretKeyFromSetupUrl(url),
                bodyText = response.body<String>(),
            )

        return parsed.map { data ->
            SyncData(
                nowMs = System.currentTimeMillis(),
                httpStatus = response.status.value,
                document = data.document,
                revision = data.revision,
                ttlSeconds = data.ttlSeconds,
                supportsSse = data.supportsSse,
                warningMessage = data.warningMessage,
            )
        }
    }

    /**
     * Perform one sync check.
     *
     * Reconciliation trigger rule:
     * - only when a document is fetched (HTTP 200) and its revision differs from lastKnownRevision,
     *   or when [explicit] is true.
     */
    suspend fun syncOnce(
        subscription: FeedSubscriptionEntity,
        explicit: Boolean = false
    ): SyncResult {
        if (!explicit && subscription.syncMode == com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSyncMode.MANUAL) {
            return SyncResult.Disabled(subscription)
        }
        if (subscription.isSyncTerminal) {
            return SyncResult.Terminal(subscription, subscription.lastError ?: "")
        }

        val now = System.currentTimeMillis()

        return try {
            val endpoints = WgFeedSubscriptionState.endpointsFor(subscription)
            if (endpoints.isEmpty()) {
                return persistHttpError(subscription, now, "No endpoints")
            }

            Timber.i(
                "wg-feed http: syncOnce subId=${subscription.id} feedId=${subscription.feedId} " +
                    "explicit=$explicit lastKnownRev=${subscription.lastKnownRevision} endpoints=${endpoints.size}"
            )

            // Endpoints are tried in the persisted order.
            val ordered = endpoints

            // Per spec (4.4): endpoints that trigger fallback conditions in this poll attempt.
            val failedThisAttempt = LinkedHashSet<String>()

            var lastErrorMsg: String? = null
            var sawRetriableFalse = false
            var sawRetriableTrueOrUnknown = false

            for (endpoint in ordered) {
                validateUrl(endpoint)

                val origin = WgFeedLog.endpointOrigin(endpoint)
                Timber.i(
                    "wg-feed http: GET subId=${subscription.id} endpoint=$origin" +
                        (if (!explicit && subscription.lastKnownRevision != null) " if-none-match" else "")
                )

                val response =
                    if (explicit) {
                        httpGet(endpoint, lastKnownRevision = null)
                    } else {
                        httpGet(endpoint, subscription.lastKnownRevision)
                    }

                Timber.i(
                    "wg-feed http: response subId=${subscription.id} status=${response.status.value} endpoint=$origin"
                )

                when (response.status) {
                    HttpStatusCode.OK -> {
                        val body = response.body<String>()
                        val parsed =
                            applyService.parseSuccessResponse(
                                storedAgeSecretKey = subscription.ageSecretKey,
                                bodyText = body,
                            )

                        val data = parsed.getOrElse { e ->
                            val msg = e.message ?: "Invalid wg-feed document"
                            lastErrorMsg = msg

                            val isTerminalDecrypt = applyService.isTerminalDecryptErrorMessage(msg)
                            if (isTerminalDecrypt) {
                                val updated =
                                    applyService.persistSyncError(
                                        subscription = subscription,
                                        nowMs = now,
                                        message = msg,
                                        terminal = true,
                                    )
                                return SyncResult.Terminal(updated, msg)
                            }

                            failedThisAttempt.add(endpoint)
                            sawRetriableTrueOrUnknown = true
                            continue
                        }

                        val syncData =
                            SyncData(
                                nowMs = now,
                                httpStatus = response.status.value,
                                document = data.document,
                                revision = data.revision,
                                ttlSeconds = data.ttlSeconds,
                                supportsSse = data.supportsSse,
                                warningMessage = data.warningMessage,
                            )

                        val applied =
                            applyService.applyDocument(
                                old = subscription,
                                document = syncData.document,
                                revision = syncData.revision,
                                ttlSeconds = syncData.ttlSeconds,
                                supportsSse = syncData.supportsSse,
                                nowMs = now,
                                failedEndpoints = failedThisAttempt,
                                explicit = explicit,
                            )

                        return when (applied) {
                            is WgFeedApplyService.ApplyResult.Terminal -> {
                                SyncResult.Terminal(applied.updated, applied.message)
                            }

                            is WgFeedApplyService.ApplyResult.Applied -> {
                                SyncResult.Success(
                                    subscription = applied.updated,
                                    changed = applied.revisionChanged,
                                    data = if (applied.reconciled) syncData else null,
                                )
                            }
                        }
                    }

                    HttpStatusCode.NotModified -> {
                        // Successful sync; no updated document.
                        // Still demote endpoints that triggered spec fallback conditions in this attempt.
                        val finalEndpoints =
                            WgFeedSubscriptionState.demoteFailedEndpoints(
                                ordered = endpoints,
                                failed = failedThisAttempt,
                            )

                        val checkedAt = System.currentTimeMillis()
                        val updated =
                            subscription.copy(
                                endpointsJson = WgFeedSubscriptionState.encodeEndpoints(
                                    finalEndpoints
                                ),
                                lastCheckedAtMs = checkedAt,
                                lastError = null,
                            )
                        subscriptionDao.update(updated)
                        Timber.i(
                            "wg-feed http: 304 success subId=${subscription.id} lastCheckedAtMs=$checkedAt"
                        )
                        return SyncResult.Success(updated, changed = false, data = null)
                    }

                    else -> {
                        val parsedError = parseErrorResponseOrNull(response)
                        if (parsedError != null) {
                            lastErrorMsg = parsedError.message
                            if (parsedError.retriable) {
                                // Spec 4.4 fallback condition: valid wg-feed JSON error with retriable=true.
                                failedThisAttempt.add(endpoint)
                                sawRetriableTrueOrUnknown = true
                                continue
                            } else {
                                // retriable=false: do not mark as failed-for-fallback; still try others.
                                sawRetriableFalse = true
                                continue
                            }
                        }

                        // Spec 4.4 fallback condition: request failed without a valid wg-feed JSON error response.
                        lastErrorMsg = "HTTP ${response.status.value}"
                        failedThisAttempt.add(endpoint)
                        sawRetriableTrueOrUnknown = true
                        continue
                    }
                }
            }

            // Terminal due to retriable=false only if that's the only kind of wg-feed error we saw.
            if (sawRetriableFalse && !sawRetriableTrueOrUnknown) {
                return persistTerminal(subscription, now, lastErrorMsg ?: "Terminal error")
            }

            persistHttpError(subscription, now, lastErrorMsg ?: "Sync failed")
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "Unknown error"
            val updated =
                applyService.persistSyncError(
                    subscription = subscription,
                    nowMs = now,
                    message = msg,
                    terminal = false,
                )
            SyncResult.Error(updated, msg)
        }
    }

    private fun validateUrl(url: String) {
        if (BuildConfig.DEBUG) {
            WgFeedValidation.requireHttpOrHttpsUrl(url)
        } else {
            WgFeedValidation.requireHttpsUrl(url)
        }
    }

    private fun ifNoneMatchValue(revision: String): String {
        // Draft-00 requires strong ETag format: "<revision>".
        // If caller already stored quotes, preserve them.
        val trimmed = revision.trim()
        return if (trimmed.startsWith('"') && trimmed.endsWith('"')) trimmed else "\"$trimmed\""
    }

    private suspend fun httpGet(url: String, lastKnownRevision: String?): HttpResponse {
        val requestUrl = WgFeedEndpoints.stripFragment(url)
        return httpClient.get(requestUrl) {
            headers {
                set(HttpHeaders.Accept, "application/json")
                lastKnownRevision?.let { append(HttpHeaders.IfNoneMatch, ifNoneMatchValue(it)) }
            }
            header(HttpHeaders.CacheControl, "no-cache")
        }
    }

    private data class ParsedError(val message: String, val retriable: Boolean)

    private suspend fun parseErrorResponseOrNull(response: HttpResponse): ParsedError? {
        val bodyText = runCatching { response.body<String>() }.getOrNull() ?: return null
        val err =
            runCatching {
                WgFeedJson.json.decodeFromString(WgFeedErrorResponse.serializer(), bodyText)
            }.getOrNull() ?: return null

        return try {
            WgFeedValidation.validateErrorResponse(err)
            ParsedError(err.message, err.retriable)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private suspend fun readErrorMessage(response: HttpResponse): String {
        return parseErrorResponseOrNull(response)?.message ?: "HTTP ${response.status.value}"
    }

    private suspend fun persistTerminal(
        subscription: FeedSubscriptionEntity,
        now: Long,
        message: String,
    ): SyncResult {
        val updated =
            subscription.copy(
                lastCheckedAtMs = now,
                lastError = message,
                isSyncTerminal = true,
            )
        subscriptionDao.update(updated)
        return SyncResult.Terminal(updated, message)
    }

    private suspend fun persistHttpError(
        subscription: FeedSubscriptionEntity,
        now: Long,
        message: String,
    ): SyncResult {
        val updated =
            subscription.copy(
                lastCheckedAtMs = now,
                lastError = message,
            )
        subscriptionDao.update(updated)
        return SyncResult.Error(updated, message)
    }
}
