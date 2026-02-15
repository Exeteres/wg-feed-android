package com.zaneschepke.wireguardautotunnel.wgfeed.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedSubscriptionDao
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedSyncer
import timber.log.Timber
import java.util.concurrent.TimeUnit

class WgFeedSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val subscriptionDao: FeedSubscriptionDao,
    private val syncer: WgFeedSyncer,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "wg_feed_sync"
        private const val KEY_SUBSCRIPTION_ID = "subscription_id"

        fun start(context: Context) {
            Timber.i("wg-feed sync: enqueue periodic worker tag=$TAG")
            val request =
                PeriodicWorkRequestBuilder<WgFeedSyncWorker>(
                    repeatInterval = 15,
                    repeatIntervalTimeUnit = TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            Timber.i("wg-feed sync: cancel periodic worker tag=$TAG")
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }
    }

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val targetSubscriptionId = inputData.getInt(KEY_SUBSCRIPTION_ID, -1)

        Timber.i(
            "wg-feed sync: doWork start targetSubId=$targetSubscriptionId at=$now runAttempt=$runAttemptCount"
        )

        val subs =
            if (targetSubscriptionId > 0) {
                val one = subscriptionDao.getById(targetSubscriptionId)
                if (one == null) {
                    Timber.w("wg-feed sync: target subscription not found id=$targetSubscriptionId")
                    emptyList()
                } else {
                    listOf(one)
                }
            } else {
                subscriptionDao.getPollingSubscriptions()
            }

        Timber.i("wg-feed sync: selected ${subs.size} polling subscription(s)")

        subs.forEach { sub ->
            val lastChecked = sub.lastCheckedAtMs
            val dueInMs = (sub.ttlSeconds * 1000L) - (now - lastChecked)

            if (lastChecked != 0L && now - lastChecked < sub.ttlSeconds * 1000L) {
                Timber.i(
                    "wg-feed sync: skip subId=${sub.id} feedId=${sub.feedId} " +
                        "ttlSec=${sub.ttlSeconds} lastChecked=$lastChecked dueInMs=${dueInMs.coerceAtLeast(0L)}"
                )

                // Still reschedule when running as one-time (e.g., if WorkManager ran early).
                if (targetSubscriptionId > 0) {
                    WgFeedPollingScheduler.schedule(applicationContext, sub)
                }
                return@forEach
            }

            Timber.i(
                "wg-feed sync: syncing subId=${sub.id} feedId=${sub.feedId} ttlSec=${sub.ttlSeconds} lastChecked=$lastChecked"
            )

            val result = syncer.syncOnce(sub, explicit = false)
            Timber.i(
                "wg-feed sync: result subId=${sub.id} type=${result::class.simpleName} " +
                    "lastCheckedBefore=$lastChecked"
            )

            // For one-time polling, reschedule the next run based on updated timestamps/ttl.
            if (targetSubscriptionId > 0) {
                val latest = subscriptionDao.getById(sub.id) ?: sub
                WgFeedPollingScheduler.schedule(applicationContext, latest)
            }
        }

        Timber.i("wg-feed sync: doWork complete")
        return Result.success()
    }
}
