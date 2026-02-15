package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedDisplayInfo
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedDocument
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedTunnel
import com.zaneschepke.wireguardautotunnel.wgfeed.model.WgFeedValidation
import org.junit.Assert.assertThrows
import org.junit.Test

class WgFeedValidationTest {

    private fun minimalDoc(
        endpoints: List<String> = listOf("https://a.example/feed"),
        iconUrl: String? = null,
    ): WgFeedDocument {
        return WgFeedDocument(
            id = "123e4567-e89b-12d3-a456-426614174000",
            endpoints = endpoints,
            warningMessage = null,
            displayInfo = WgFeedDisplayInfo(title = "Test", iconUrl = iconUrl),
            tunnels =
                listOf(
                    WgFeedTunnel(
                        id = "t1",
                        name = "test",
                        displayInfo = WgFeedDisplayInfo(title = "Tunnel"),
                        wgQuickConfig = "[Interface]\nPrivateKey = x\n",
                    ),
                ),
        )
    }

    @Test
    fun validateDocument_rejectsEndpointWithFragment() {
        val doc = minimalDoc(endpoints = listOf("https://a.example/feed#fragment"))
        assertThrows(IllegalArgumentException::class.java) {
            WgFeedValidation.validateDocument(doc)
        }
    }

    @Test
    fun validateDocument_rejectsNonHttpsEndpoint() {
        val doc = minimalDoc(endpoints = listOf("http://a.example/feed"))
        assertThrows(IllegalArgumentException::class.java) {
            WgFeedValidation.validateDocument(doc)
        }
    }

    @Test
    fun validateDocument_rejectsNonSvgDataIconUrl() {
        val doc = minimalDoc(iconUrl = "data:image/png;base64,AAAA")
        assertThrows(IllegalArgumentException::class.java) {
            WgFeedValidation.validateDocument(doc)
        }
    }

    @Test
    fun validateDocument_allowsSvgDataIconUrl() {
        val doc = minimalDoc(iconUrl = "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=")
        WgFeedValidation.validateDocument(doc)
    }
}
