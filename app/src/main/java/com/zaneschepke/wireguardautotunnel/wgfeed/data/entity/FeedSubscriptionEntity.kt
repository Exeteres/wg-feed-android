package com.zaneschepke.wireguardautotunnel.wgfeed.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class FeedSyncMode {
    MANUAL,
    POLLING,
    REALTIME,
}

@Entity(
    tableName = "feed_subscription",
    indices = [Index(value = ["feed_id"], unique = true)],
)
data class FeedSubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "feed_id") val feedId: String,

    /** JSON-encoded List<String> of endpoints[] from the feed document. */
    @ColumnInfo(name = "endpoints_json", defaultValue = "'[]'") val endpointsJson: String = "[]",

    /**
     * When encryption is used, the client MUST store key material extracted from the Setup URL fragment.
     * Stored as the full AGE secret key string (e.g. AGE-SECRET-KEY-...).
     */
    @ColumnInfo(name = "age_secret_key") val ageSecretKey: String? = null,

    /**
     * Whether to ignore enabled/disabled state from the server feed document.
     */
    @ColumnInfo(name = "ignore_server_state") val ignoreServerState: Boolean = false,
    @ColumnInfo(name = "sync_mode") val syncMode: FeedSyncMode = FeedSyncMode.POLLING,

    /** Last time we attempted a sync/check (including 304/errors). */
    @ColumnInfo(name = "last_checked_at_ms") val lastCheckedAtMs: Long = 0L,

    /** Last time we successfully applied server state into local tunnels (revision-change or explicit sync). */
    @ColumnInfo(name = "last_synced_at_ms", defaultValue = "0") val lastSyncedAtMs: Long = 0L,

    /**
     * Latest server-provided revision we have successfully fetched and validated and then attempted
     * to apply (reconcile) locally.
     *
     * This is not a guarantee that reconciliation succeeded; it is the last revision we treated as
     * 'latest-known' for sync purposes (e.g., for conditional requests via If-None-Match).
     */
    @ColumnInfo(name = "last_known_revision") val lastKnownRevision: String? = null,
    @ColumnInfo(name = "ttl_seconds") val ttlSeconds: Int,
    @ColumnInfo(name = "supports_sse", defaultValue = "0") val supportsSse: Boolean = false,

    @ColumnInfo(name = "display_title") val displayTitle: String,
    @ColumnInfo(name = "display_description") val displayDescription: String? = null,
    @ColumnInfo(name = "display_icon_url") val displayIconUrl: String? = null,

    @ColumnInfo(name = "warning_message") val warningMessage: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,

    @ColumnInfo(name = "is_sync_terminal", defaultValue = "0") val isSyncTerminal: Boolean = false,
)
