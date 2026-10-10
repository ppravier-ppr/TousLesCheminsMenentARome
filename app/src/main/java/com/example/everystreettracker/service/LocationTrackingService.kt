package com.example.everystreettracker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.everystreettracker.MainActivity
import com.example.everystreettracker.data.db.AppDatabase
import com.example.everystreettracker.data.db.TrackEntity
import com.example.everystreettracker.data.db.TrackPointEntity
import com.example.everystreettracker.sync.GoogleDriveSyncManager
import com.example.everystreettracker.sync.GpxExporter
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocationTrackingService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var db: AppDatabase
    private lateinit var driveSyncManager: GoogleDriveSyncManager

    private var activeTrackId: Long = -1L
    private var lastLocation: Location? = null
    private var totalDistanceMeters: Double = 0.0

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        driveSyncManager = GoogleDriveSyncManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    processNewLocation(location)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_TRACKING
        when (action) {
            ACTION_START_TRACKING -> startTracking()
            ACTION_STOP_TRACKING -> stopTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (_isTracking.value) return

        try {
            val notification = createNotification("Enregistrement actif de vos déplacements...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isTracking.value = true
        } catch (e: Exception) {
            e.printStackTrace()
            // Continue gracefully even if notification cannot be shown in foreground
            _isTracking.value = true
        }

        serviceScope.launch {
            val existingActive = db.trackDao().getActiveTrack()
            if (existingActive != null) {
                activeTrackId = existingActive.id
                totalDistanceMeters = existingActive.distanceMeters
                _distanceMeters.value = totalDistanceMeters
                val lastPoint = db.trackDao().getPointsForTrack(existingActive.id).lastOrNull()
                if (lastPoint != null) {
                    lastLocation = Location("database").apply {
                        latitude = lastPoint.latitude
                        longitude = lastPoint.longitude
                        altitude = lastPoint.altitude
                        time = lastPoint.timestamp
                    }
                } else {
                    lastLocation = null
                }
            } else {
                val title = "Trajet du " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
                val newTrack = TrackEntity(title = title)
                activeTrackId = db.trackDao().insertTrack(newTrack)
                totalDistanceMeters = 0.0
                _distanceMeters.value = 0.0
                lastLocation = null
            }
            candidateRelocationPoints.clear()
            requestLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1500L)
            .setMinUpdateDistanceMeters(2.0f)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val candidateRelocationPoints = mutableListOf<Location>()

    private fun processNewLocation(location: Location) {
        if (activeTrackId <= 0) return

        // 1. Filter out fixes with poor accuracy (e.g. inside buildings, tunnels, urban canyons)
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_METERS) {
            return
        }

        // 2. Filter out fixes with native speed exceeding maximum human running speed
        if (location.hasSpeed() && location.speed > MAX_HUMAN_SPEED_MPS) {
            handleSuspiciousLocation(location)
            return
        }

        serviceScope.launch {
            val prev = lastLocation
            if (prev == null) {
                // First valid fix for this tracking session
                lastLocation = location
                candidateRelocationPoints.clear()
                _currentLocation.value = location
                saveTrackPoint(location, totalDistanceMeters)
                return@launch
            }

            val stepDistance = prev.distanceTo(location).toDouble()

            // 3. Filter out stationary micro-jitter (standing still at traffic lights, resting)
            if (stepDistance < MIN_STEP_DISTANCE_METERS) {
                return@launch
            }

            // 4. Calculate implied speed between consecutive fixes
            val timeDeltaMs = location.time - prev.time
            val timeDeltaSeconds = if (timeDeltaMs > 100) {
                timeDeltaMs / 1000.0
            } else {
                1.5 // safe fallback for near-zero or non-monotonic timestamps
            }
            val impliedSpeedMps = stepDistance / timeDeltaSeconds

            // 5. Reject if implied speed exceeds maximum human running speed (32.4 km/h)
            // OR if an unrecorded massive leap occurred (e.g. while in a building for several minutes,
            // where time elapsed artificially reduces speed, but stepDistance is >= 150m)
            val isSingleStepTooLarge = stepDistance >= 150.0 && timeDeltaSeconds > 30.0
            if (impliedSpeedMps > MAX_HUMAN_SPEED_MPS || isSingleStepTooLarge) {
                handleSuspiciousLocation(location)
                return@launch
            }

            // Fix is valid human movement!
            // Clear any transient candidate glitch buffer (e.g. temporary cell tower jump that just recovered)
            candidateRelocationPoints.clear()
            lastLocation = location
            _currentLocation.value = location
            totalDistanceMeters += stepDistance
            _distanceMeters.value = totalDistanceMeters

            saveTrackPoint(location, totalDistanceMeters)
        }
    }

    private fun handleSuspiciousLocation(location: Location) {
        val lastCand = candidateRelocationPoints.lastOrNull()
        if (lastCand != null) {
            val dist = lastCand.distanceTo(location)
            val dt = kotlin.math.max(1.0, (location.time - lastCand.time) / 1000.0)
            if (dist / dt <= MAX_HUMAN_SPEED_MPS) {
                candidateRelocationPoints.add(location)
            } else {
                candidateRelocationPoints.clear()
                candidateRelocationPoints.add(location)
            }
        } else {
            candidateRelocationPoints.add(location)
        }

        // Only relocate if user has accumulated at least 8 continuous coherent fixes
        // AND moved at least 15m in the new location (preventing stationary cell-tower spikes)
        if (candidateRelocationPoints.size >= 8) {
            val firstCand = candidateRelocationPoints.first()
            val totalDisplacement = firstCand.distanceTo(location)
            if (totalDisplacement >= 15.0f) {
                lastLocation = location
                _currentLocation.value = location
                candidateRelocationPoints.clear()
                serviceScope.launch {
                    saveTrackPoint(location, totalDistanceMeters)
                }
            }
        }
    }

    private suspend fun saveTrackPoint(location: Location, distanceMeters: Double) {
        val point = TrackPointEntity(
            trackId = activeTrackId,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            speed = location.speed,
            timestamp = System.currentTimeMillis()
        )
        db.trackDao().insertTrackPoint(point)

        val currentTrack = db.trackDao().getActiveTrack()
        if (currentTrack != null) {
            db.trackDao().updateTrack(
                currentTrack.copy(
                    distanceMeters = distanceMeters,
                    endTime = System.currentTimeMillis()
                )
            )
        }
    }

    private fun stopTracking() {
        if (!_isTracking.value) return
        _isTracking.value = false

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        serviceScope.launch {
            if (activeTrackId > 0) {
                val track = db.trackDao().getActiveTrack()
                if (track != null) {
                    val finalTrack = track.copy(
                        isActive = false,
                        endTime = System.currentTimeMillis()
                    )
                    db.trackDao().updateTrack(finalTrack)
                    
                    // Auto-export and upload
                    val points = db.trackDao().getPointsForTrack(finalTrack.id)
                    if (points.isNotEmpty()) {
                        val file = GpxExporter.saveGpxToFile(this@LocationTrackingService, finalTrack, points)

                        if (driveSyncManager.hasConfiguredDriveFolder()) {
                            driveSyncManager.saveTrackToSafFolder(finalTrack)
                        }

                        val account = GoogleSignIn.getLastSignedInAccount(this@LocationTrackingService)
                        if (account != null) {
                            val result = driveSyncManager.initializeWithAccount(account)
                            if (result.isSuccess) {
                                driveSyncManager.uploadTrack(file)
                                db.trackDao().updateTrack(finalTrack.copy(isActive = false, isSynced = true))
                            }
                        }
                    }
                }
            }
            activeTrackId = -1L
            lastLocation = null
            candidateRelocationPoints.clear()
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Suivi de déplacements GPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification permanente pendant l'enregistrement de votre trajet"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TousLesCheminsMenentARome")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "every_street_tracker_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_TRACKING = "com.example.everystreettracker.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.everystreettracker.STOP_TRACKING"

        // Maximum human running/sprinting speed: 9.0 m/s ≈ 32.4 km/h
        const val MAX_HUMAN_SPEED_MPS = 9.0f
        // Reject GPS points with accuracy radius worse than 25 meters
        const val MAX_ACCURACY_METERS = 25.0f
        // Minimum displacement to record a new point (avoids GPS stationary jitter)
        const val MIN_STEP_DISTANCE_METERS = 2.0

        private val _currentLocation = MutableStateFlow<Location?>(null)
        val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

        private val _isTracking = MutableStateFlow(false)
        val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

        private val _distanceMeters = MutableStateFlow(0.0)
        val distanceMeters: StateFlow<Double> = _distanceMeters.asStateFlow()
    }
}
