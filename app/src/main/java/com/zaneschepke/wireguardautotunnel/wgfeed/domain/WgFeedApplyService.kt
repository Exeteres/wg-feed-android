package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedSubscriptionDao
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedDocument
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedSuccessResponse
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedValidation
import kotlinx.serialization.SerializationException
import timber.log.Timber

/**
 * Base wg-feed apply service.
 *
 * Owns the core logic that given a freshly fetched+validated document:
 * - updates the subscription DB row (metadata, endpoints ordering/demotion, latest-known revision)
 * - triggers reconciliation when revision changed (or explicit)
 */
class WgFeedApplyService(
    private val subscriptionDao: FeedSubscriptionDao,
    private val reconciler: WgFeedReconciler,
) {

    sealed class ApplyResult {
        data class Applied(
            val updated: FeedSubscriptionEntity,
            val reconciled: Boolean,
            val revisionChanged: Boolean,
        ) : ApplyResult()

        data class Terminal(val updated: FeedSubscriptionEntity, val message: String) :
            ApplyResult()
    }

    suspend fun applyDocument(
        old: FeedSubscriptionEntity,
        document: WgFeedDocument,
        revision: String,
        ttlSeconds: Int,
        supportsSse: Boolean,
        nowMs: Long,
        failedEndpoints: Set<String>,
        explicit: Boolean,
    ): ApplyResult {
        // Terminal if feed_id changes for an existing subscription.
        if (old.feedId.isNotBlank() && old.feedId != document.id) {
            val updated =
                old.copy(
                    lastCheckedAtMs = nowMs,
                    lastError = "Feed ID changed",
                    isSyncTerminal = true,
                )
            subscriptionDao.update(updated)
            return ApplyResult.Terminal(updated, "Feed ID changed")
        }

        val localEndpoints = WgFeedSubscriptionState.endpointsFor(old)
        val mergedEndpoints =
            WgFeedSubscriptionState.mergeEndpointsPreserveLocalOrder(
                local = localEndpoints,
                server = document.endpoints,
            )

        val finalEndpoints =
            WgFeedSubscriptionState.demoteFailedEndpoints(
                ordered = mergedEndpoints,
                failed = failedEndpoints,
            )

        val revisionChanged = old.lastKnownRevision != revision

        Timber.i(
            "wg-feed apply: subId=${old.id} feedId=${old.feedId} nowMs=$nowMs " +
                "revChanged=$revisionChanged explicit=$explicit ttlSec=$ttlSeconds failedEndpoints=${failedEndpoints.size}"
        )

        val updated =
            old.copy(
                feedId = document.id,
                endpointsJson = WgFeedSubscriptionState.encodeEndpoints(finalEndpoints),
                lastCheckedAtMs = nowMs,
                // The most recent revision for which this client has successfully fetched a valid document
                // and attempted to apply it (i.e., persisted as latest-known).
                lastKnownRevision = revision,
                ttlSeconds = ttlSeconds,
                supportsSse = supportsSse,
                displayTitle = document.displayInfo.title,
                displayDescription = document.displayInfo.description,
                displayIconUrl = document.displayInfo.iconUrl,
                warningMessage = document.warningMessage,
                lastError = null,
                isSyncTerminal = false,
            )

        subscriptionDao.update(updated)

        val shouldReconcile = explicit || revisionChanged
        Timber.i(
            "wg-feed apply: subId=${old.id} updated lastCheckedAtMs=${updated.lastCheckedAtMs} shouldReconcile=$shouldReconcile"
        )

        if (shouldReconcile) {
            reconciler.reconcile(updated, document)

            subscriptionDao.update(
                updated.copy(
                    lastSyncedAtMs = nowMs,
                ),
            )
            Timber.i(
                "wg-feed apply: subId=${old.id} reconcile attempted lastSyncedAtMs=$nowMs"
            )
        }

        return ApplyResult.Applied(
            updated,
            reconciled = shouldReconcile,
            revisionChanged = revisionChanged
        )
    }

    fun isTerminalDecryptErrorMessage(message: String): Boolean {
        return message.contains("Missing encryption key", ignoreCase = true) ||
                message.contains("Decryption failed", ignoreCase = true) ||
                message.contains("Invalid decrypted feed JSON", ignoreCase = true)
    }

    fun withSyncError(
        subscription: FeedSubscriptionEntity,
        nowMs: Long,
        message: String,
        terminal: Boolean,
    ): FeedSubscriptionEntity {
        return subscription.copy(
            lastCheckedAtMs = nowMs,
            lastError = message,
            isSyncTerminal = terminal || subscription.isSyncTerminal,
        )
    }

    suspend fun persistSyncError(
        subscription: FeedSubscriptionEntity,
        nowMs: Long,
        message: String,
        terminal: Boolean,
    ): FeedSubscriptionEntity {
        val updated = withSyncError(
            subscription = subscription,
            nowMs = nowMs,
            message = message,
            terminal = terminal,
        )
        subscriptionDao.update(updated)
        return updated
    }

    data class ParsedSuccess(
        val document: WgFeedDocument,
        val revision: String,
        val ttlSeconds: Int,
        val supportsSse: Boolean,
        val warningMessage: String?,
    )

    fun parseSuccessResponse(
        storedAgeSecretKey: String?,
        bodyText: String,
    ): Result<ParsedSuccess> {
        val resp =
            try {
                WgFeedJson.json.decodeFromString(WgFeedSuccessResponse.serializer(), bodyText)
            } catch (_: SerializationException) {
                return Result.failure(IllegalArgumentException("Invalid wg-feed JSON"))
            }

        return try {
            WgFeedValidation.validateSuccessResponse(resp)

            val doc: WgFeedDocument =
                if (resp.encrypted) {
                    val identity =
                        WgFeedAge.identityFromSecretKey(storedAgeSecretKey)
                            ?: return Result.failure(IllegalArgumentException("Missing encryption key"))

                    val plaintext =
                        try {
                            WgFeedAge.decryptArmored(resp.encryptedData ?: "", identity)
                        } catch (e: Exception) {
                            return Result.failure(
                                IllegalArgumentException(
                                    e.message ?: "Decryption failed"
                                )
                            )
                        }

                    val parsedDoc =
                        try {
                            WgFeedJson.json.decodeFromString(WgFeedDocument.serializer(), plaintext)
                        } catch (_: SerializationException) {
                            return Result.failure(IllegalArgumentException("Invalid decrypted feed JSON"))
                        }

                    parsedDoc
                } else {
                    resp.data!!
                }

            WgFeedValidation.validateDocument(doc)

            Result.success(
                ParsedSuccess(
                    document = doc,
                    revision = resp.revision,
                    ttlSeconds = resp.ttlSeconds,
                    supportsSse = resp.supportsSse,
                    warningMessage = doc.warningMessage,
                ),
            )
        } catch (e: IllegalArgumentException) {
            Result.failure(IllegalArgumentException(e.message ?: "Invalid wg-feed document"))
        }
    }
}
