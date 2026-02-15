package com.zaneschepke.wireguardautotunnel.di

import com.zaneschepke.wireguardautotunnel.core.worker.ServiceWorker
import com.zaneschepke.wireguardautotunnel.wgfeed.worker.WgFeedRealtimeWorker
import com.zaneschepke.wireguardautotunnel.wgfeed.worker.WgFeedSyncWorker
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.module

val workerModule = module {
    workerOf(::ServiceWorker)
    workerOf(::WgFeedSyncWorker)
    workerOf(::WgFeedRealtimeWorker)
}
