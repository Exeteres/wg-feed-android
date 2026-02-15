package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import kotlinx.serialization.SerializationException

/** Helpers for persisting Draft-00 subscription state. */
object WgFeedSubscriptionState {
    fun decodeEndpoints(endpointsJson: String): List<String> {
        if (endpointsJson.isBlank()) return emptyList()
        return try {
            WgFeedJson.json.decodeFromString(ListSerializer, endpointsJson)
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    fun encodeEndpoints(endpoints: List<String>): String {
        return WgFeedJson.json.encodeToString(ListSerializer, endpoints)
    }

    /** Effective endpoints list for a subscription (server-provided endpoints[]). */
    fun endpointsFor(subscription: FeedSubscriptionEntity): List<String> {
        return decodeEndpoints(subscription.endpointsJson)
    }

    /**
     * Merge server endpoints into the local list while preserving local order.
     *
     * Rules:
     * - Server defines the set of endpoints.
     * - Client keeps its current ordering for endpoints that still exist on the server.
     * - Endpoints removed by the server are removed locally.
     * - New endpoints (present on server, absent locally) are appended (in server order).
     */
    fun mergeEndpointsPreserveLocalOrder(
        local: List<String>,
        server: List<String>,
    ): List<String> {
        if (server.isEmpty()) return emptyList()

        val serverSet = server.toHashSet()

        val out = ArrayList<String>(server.size)
        val seen = HashSet<String>(server.size)

        // Keep local order, but only for endpoints still present on server.
        local.forEach { ep ->
            if (serverSet.contains(ep) && seen.add(ep)) out.add(ep)
        }

        // Append any new server endpoints not already present, in server order.
        server.forEach { ep ->
            if (seen.add(ep)) out.add(ep)
        }

        return out
    }

    /**
     * Given an ordered endpoint list, returns a new list where endpoints in [failed] are moved
     * to the end, preserving relative order in both groups.
     */
    fun demoteFailedEndpoints(
        ordered: List<String>,
        failed: Set<String>,
    ): List<String> {
        if (failed.isEmpty() || ordered.isEmpty()) return ordered

        val ok = ArrayList<String>(ordered.size)
        val bad = ArrayList<String>(ordered.size)
        ordered.forEach { ep ->
            if (failed.contains(ep)) bad.add(ep) else ok.add(ep)
        }
        ok.addAll(bad)
        return ok
    }

    private val ListSerializer =
        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
}
