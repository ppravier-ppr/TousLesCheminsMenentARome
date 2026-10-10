package com.example.everystreettracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity): Long

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE isActive = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getActiveTrack(): TrackEntity?

    @Query("SELECT * FROM tracks WHERE isActive = 1 ORDER BY id DESC LIMIT 1")
    fun observeActiveTrack(): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks ORDER BY startTime DESC")
    fun getAllTracksFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY startTime DESC")
    suspend fun getAllTracks(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPoint(point: TrackPointEntity): Long

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    fun getPointsForTrackFlow(trackId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    suspend fun getPointsForTrack(trackId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points ORDER BY timestamp ASC")
    fun getAllTrackPointsFlow(): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points ORDER BY timestamp ASC")
    suspend fun getAllTrackPoints(): List<TrackPointEntity>

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: Long)

    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    suspend fun deletePointsForTrack(trackId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPoints(points: List<TrackPointEntity>)
}
