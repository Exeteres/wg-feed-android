package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WgFeedEndpointsTest {

    @Test
    fun ordered_returnsOriginalOrder_whenNoPreferred() {
        val endpoints = listOf("https://a.example/feed", "https://b.example/feed")
        assertEquals(endpoints, WgFeedEndpoints.ordered(endpoints, preferred = null))
        assertEquals(endpoints, WgFeedEndpoints.ordered(endpoints, preferred = ""))
    }

    @Test
    fun ordered_movesPreferredToFront_whenPreferredInList() {
        val endpoints = listOf("https://a.example/feed", "https://b.example/feed", "https://c.example/feed")
        val ordered = WgFeedEndpoints.ordered(endpoints, preferred = "https://b.example/feed")
        assertEquals(listOf("https://b.example/feed", "https://a.example/feed", "https://c.example/feed"), ordered)
    }

    @Test
    fun ordered_keepsOrder_whenPreferredNotInList() {
        val endpoints = listOf("https://a.example/feed", "https://b.example/feed")
        val ordered = WgFeedEndpoints.ordered(endpoints, preferred = "https://x.example/feed")
        assertEquals(endpoints, ordered)
    }
}
