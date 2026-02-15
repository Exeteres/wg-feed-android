package com.zaneschepke.wireguardautotunnel.di

import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedApplyService
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedRealtimeSyncer
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedReconciler
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedSseClient
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedSseHub
import com.zaneschepke.wireguardautotunnel.wgfeed.domain.WgFeedSyncer
import org.koin.core.qualifier.named
import org.koin.dsl.module

val wgFeedModule = module {
    single { WgFeedApplyService(get(), get()) }

    single { WgFeedSyncer(get(), get(), get()) }
    single { WgFeedReconciler(get(), get(), get()) }

    factory { WgFeedSseClient(get(named("sse"))) }
    single { WgFeedSseHub { get<WgFeedSseClient>() } }

    single { WgFeedRealtimeSyncer(get(), get(), get()) }
}
