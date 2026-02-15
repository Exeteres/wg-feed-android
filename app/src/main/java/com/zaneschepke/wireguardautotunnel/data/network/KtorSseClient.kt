package com.zaneschepke.wireguardautotunnel.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE

/**
 * Dedicated HttpClient for Server-Sent Events (wg-feed realtime).
 * Uses CIO engine and installs the Ktor SSE plugin.
 */
object KtorSseClient {
    fun create(): HttpClient {
        return HttpClient(CIO) {
            install(SSE)

            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
    }
}