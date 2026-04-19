package com.travelguide.anywhere.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mentioned_places")
data class MentionedPlaceEntity(
    @PrimaryKey val osmId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val sessionId: String,
    val mentionedAt: Long = System.currentTimeMillis()
)
