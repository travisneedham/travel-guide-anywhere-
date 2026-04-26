package com.travelguide.anywhere.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MentionedPlaceDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(place: MentionedPlaceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(places: List<MentionedPlaceEntity>)

    @Query("SELECT osmId FROM mentioned_places WHERE sessionId = :sessionId")
    suspend fun getOsmIdsBySession(sessionId: String): List<String>

    @Query("SELECT name FROM mentioned_places WHERE sessionId = :sessionId")
    suspend fun getNamesBySession(sessionId: String): List<String>

    @Query("SELECT * FROM mentioned_places WHERE sessionId = :sessionId ORDER BY mentionedAt DESC")
    suspend fun getBySession(sessionId: String): List<MentionedPlaceEntity>

    @Query("DELETE FROM mentioned_places WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM mentioned_places")
    suspend fun deleteAll()
}
