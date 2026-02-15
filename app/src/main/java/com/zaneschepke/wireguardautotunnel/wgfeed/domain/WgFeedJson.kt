package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import kotlinx.serialization.json.Json

object WgFeedJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
}
