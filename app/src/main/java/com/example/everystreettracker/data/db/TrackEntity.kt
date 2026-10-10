package com.example.everystreettracker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val distanceMeters: Double = 0.0,
    val isActive: Boolean = true,
    val isSynced: Boolean = false
)
