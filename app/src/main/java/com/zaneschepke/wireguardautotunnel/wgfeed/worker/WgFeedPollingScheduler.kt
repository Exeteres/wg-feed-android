package com.zaneschepke.wireguardautotunnel.wgfeed.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Schedules wg-feed polling syncs using one-time WorkManager requests.
 *
 * Notes:
 * - WorkManager delays are best-effort and may drift (Doze/batching).
 * - We keep this scheduler for TTL-based timing; the actual sync work is done in [WgFeedSyncWorker].
 */
object WgFeedPollingScheduler {

    private const val KEY_SUBSCRIPTION_ID = "subscription_id"

    fun cancelAll(context: Context) {
        Timber.i("wg-feed polling: cancelAll")
        WorkManager.getInstance(context).cancelAllWorkByTag("wg_feed_polling")
    }

    fun schedule(context: Context, subscription: FeedSubscriptionEntity) {
        val id = subscription.id
        if (id <= 0) return

        val workName = "wg_feed_polling_$id"

        val intervalSeconds =
            subscription.ttlSeconds
                .toLong()
                .coerceAtLeast(0L)

        val last = subscription.lastCheckedAtMs
        val now = System.currentTimeMillis()

        val nextAtMs =
            if (last <= 0L) {
                // If never checked yet, run soon.
                now
            } else {
                last + intervalSeconds * 1000L
            }

        val delayMs = (nextAtMs - now).coerceAtLeast(0L)

        Timber.i(
            "wg-feed polling: schedule subId=$id work=$workName ttlSec=${subscription.ttlSeconds} " +
                "lastChecked=$last nextAt=$nextAtMs delayMs=$delayMs"
        )

        val req =
            OneTimeWorkRequestBuilder<WgFeedSyncWorker>()
                .addTag("wg_feed_polling")
                .addTag(workName)
                .setInputData(workDataOf(KEY_SUBSCRIPTION_ID to id))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, req)
    }
}
