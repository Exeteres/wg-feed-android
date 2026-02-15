package com.zaneschepke.wireguardautotunnel.wgfeed.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedSubscriptionDao {
    @Query("SELECT * FROM feed_subscription ORDER BY id DESC")
    fun getAllFlow(): Flow<List<FeedSubscriptionEntity>>

    @Query("SELECT * FROM feed_subscription WHERE id = :id")
    suspend fun getById(id: Int): FeedSubscriptionEntity?

    @Query("SELECT * FROM feed_subscription WHERE feed_id = :feedId")
    suspend fun getByFeedId(feedId: String): FeedSubscriptionEntity?

    @Upsert
    suspend fun upsert(entity: FeedSubscriptionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: FeedSubscriptionEntity): Long

    @Update
    suspend fun update(entity: FeedSubscriptionEntity)

    @Delete
    suspend fun delete(entity: FeedSubscriptionEntity)

    @Query("SELECT * FROM feed_subscription WHERE sync_mode = 1 AND is_sync_terminal = 0")
    suspend fun getPollingSubscriptions(): List<FeedSubscriptionEntity>

    @Query("SELECT * FROM feed_subscription WHERE sync_mode = 2 AND is_sync_terminal = 0")
    suspend fun getRealtimeSubscriptions(): List<FeedSubscriptionEntity>
}
