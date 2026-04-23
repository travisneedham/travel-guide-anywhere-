package com.travelguide.anywhere.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MentionedPlaceEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TourDatabase : RoomDatabase() {
    abstract fun mentionedPlaceDao(): MentionedPlaceDao
}
