package com.zaneschepke.wireguardautotunnel.wgfeed.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.notification.WireGuardNotification
import com.zaneschepke.wireguardautotunnel.wgfeed.data.dao.FeedSubscriptionDao
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedRealtimeSyncer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Singleton long-running foreground worker that maintains wg-feed realtime (SSE) connections
 * for all eligible subscriptions.
 *
 * Eligibility:
 * - enabled
 * - syncMode == REALTIME
 * - !syncTerminal
 */
class WgFeedRealtimeWorker(
    context: Context,
    params: WorkerParameters,
    private val subscriptionDao: FeedSubscriptionDao,
    private val realtimeSyncer: WgFeedRealtimeSyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        // WorkManager requires foreground to be set before the worker completes.
        setForeground(createForegroundInfo(trackedCount = 0))

        Timber.i("wg-feed realtime: worker started")

        val activeIds = LinkedHashSet<Int>()
        var lastLoggedEligibleIds: Set<Int>? = null

        while (!isStopped) {
            val eligible = subscriptionDao.getRealtimeSubscriptions()

            if (eligible.isEmpty()) {
                Timber.i("wg-feed realtime: no eligible subscriptions, stopping worker")
                break
            }

            val eligibleIds = eligible.map { it.id }.toSet()
            if (eligibleIds != lastLoggedEligibleIds) {
                Timber.i("wg-feed realtime: eligibleIds=${eligibleIds.sorted()}")
                lastLoggedEligibleIds = eligibleIds
            }

            // Stop no-longer-eligible
            val toStop = activeIds.filter { it !in eligibleIds }
            toStop.forEach { id ->
                Timber.i("wg-feed realtime: stop tracking subId=$id")
                realtimeSyncer.stop(id)
                activeIds.remove(id)
            }

            // Start newly eligible
            eligible.forEach { sub ->
                if (!activeIds.contains(sub.id)) {
                    Timber.i("wg-feed realtime: start tracking subId=${sub.id} feedId=${sub.feedId}")
                    activeIds.add(sub.id)
                    realtimeSyncer.start(sub)
                }
            }

            // Update notification with count.
            try {
                setForeground(createForegroundInfo(trackedCount = activeIds.size))
            } catch (_: Throwable) {
                // worker might be stopping
            }

            delay(15_000L)
        }

        Timber.i("wg-feed realtime: worker stopping; stopAll")
        realtimeSyncer.stopAll()

        Result.success()
    }

    private fun createForegroundInfo(trackedCount: Int): ForegroundInfo {
        val notificationId = NOTIFICATION_ID

        val notification: Notification =
            WireGuardNotification(applicationContext)
                .createNotification(
                    channel = WireGuardNotification.NotificationChannels.WG_FEED_REALTIME,
                    title = applicationContext.getString(
                        R.string.wg_feed_realtime_tracking_count,
                        trackedCount
                    ),
                    actions = emptyList(),
                    showTimestamp = false,
                    importance = android.app.NotificationManager.IMPORTANCE_LOW,
                    onGoing = true,
                    onlyAlertOnce = true,
                    groupKey = "wg_feed_realtime",
                    isGroupSummary = false,
                )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        private const val WORK_NAME = "wg_feed_realtime"
        private const val NOTIFICATION_ID = 20_000

        fun start(context: Context) {
            Timber.i("wg-feed realtime: enqueue unique work")
            val req =
                androidx.work.OneTimeWorkRequestBuilder<WgFeedRealtimeWorker>()
                    .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()

            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, androidx.work.ExistingWorkPolicy.KEEP, req)
        }

        fun stop(context: Context) {
            Timber.i("wg-feed realtime: cancel unique work")
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
