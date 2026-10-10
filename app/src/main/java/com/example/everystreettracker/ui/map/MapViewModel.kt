package com.example.everystreettracker.ui.map

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.everystreettracker.data.db.AppDatabase
import com.example.everystreettracker.data.db.TrackEntity
import com.example.everystreettracker.service.LocationTrackingService
import com.example.everystreettracker.sync.GoogleDriveSyncManager
import com.example.everystreettracker.sync.GpxExporter
import com.example.everystreettracker.sync.TrackCleaner
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackItemUi(
    val id: Long,
    val title: String,
    val formattedDate: String,
    val formattedDistance: String,
    val formattedDuration: String,
    val pointsCount: Int,
    val isSynced: Boolean,
    val fileName: String,
    val localFile: File?
)

data class MapUiState(
    val activePath: List<LatLng> = emptyList(),
    val historicalPaths: List<List<LatLng>> = emptyList(),
    val isTracking: Boolean = false,
    val distanceMeters: Double = 0.0,
    val totalHistoricalDistanceMeters: Double = 0.0,
    val totalTracksCount: Int = 0,
    val totalPointsCount: Int = 0,
    val statusMessage: String? = null,
    val driveStatus: String = "Non connecté",
    val driveFolderName: String? = null,
    val hasDriveFolder: Boolean = false,
    val publicFolderPath: String = GpxExporter.getDisplayFolderPath(),
    val trackList: List<TrackItemUi> = emptyList(),
    val pendingExportTrackId: Long? = null,
    // Review mode properties
    val reviewedTrackId: Long? = null,
    val reviewedTrack: TrackItemUi? = null,
    val reviewedTrackPoints: List<LatLng> = emptyList(),
    val reviewedTrackStart: LatLng? = null,
    val reviewedTrackEnd: LatLng? = null,
    val reviewedAvgSpeedKmh: Double = 0.0,
    val reviewedMaxSpeedKmh: Double = 0.0
)

sealed class MapUiEvent {
    data class ShareGpxFile(val chooserIntent: Intent) : MapUiEvent()
    data class ShowToast(val message: String) : MapUiEvent()
    data class LaunchSignIn(val intent: Intent) : MapUiEvent()
    data class OpenUrl(val url: String) : MapUiEvent()
    data class CopyToClipboard(val text: String) : MapUiEvent()
    data class LaunchFolderPicker(val intent: Intent) : MapUiEvent()
    data class LaunchCreateDocument(val defaultFileName: String, val trackId: Long) : MapUiEvent()
    data class FocusCamera(val target: LatLng, val zoom: Float) : MapUiEvent()
    data class FocusBounds(val southwest: LatLng, val northeast: LatLng) : MapUiEvent()
    data class OpenFolder(val intent: Intent) : MapUiEvent()
}

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val trackDao = db.trackDao()
    private val driveSyncManager = GoogleDriveSyncManager(application)

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<MapUiEvent>()
    val uiEvents: SharedFlow<MapUiEvent> = _uiEvents.asSharedFlow()

    init {
        refreshDriveFolderState()
        observeTrackingService()
        observeDatabase()
        checkGoogleSignIn()
        performAutomaticMigration()
    }

    fun performAutomaticMigration() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val migratedFiles = GpxExporter.migratePrivateFilesToPublic(app)

            val tracks = trackDao.getAllTracks().filter { !it.isActive }
            var exportedFromDb = 0
            for (t in tracks) {
                val points = trackDao.getPointsForTrack(t.id)
                if (points.isNotEmpty()) {
                    GpxExporter.saveGpxToFile(app, t, points)
                    exportedFromDb++
                }
            }

            if (migratedFiles > 0 || exportedFromDb > 0) {
                _uiEvents.emit(
                    MapUiEvent.ShowToast(
                        "Anciens parcours synchronisés dans Documents/${GpxExporter.FOLDER_NAME}"
                    )
                )
            }
        }
    }

    fun refreshDriveFolderState() {
        val folderName = driveSyncManager.getDriveFolderName()
        val hasFolder = driveSyncManager.hasConfiguredDriveFolder()
        _uiState.update {
            it.copy(
                driveFolderName = folderName,
                hasDriveFolder = hasFolder,
                publicFolderPath = GpxExporter.getDisplayFolderPath()
            )
        }
    }

    private fun checkGoogleSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(getApplication())
        if (account != null) {
            initializeDrive(account)
        }
    }

    fun onSignInResult(account: GoogleSignInAccount?) {
        if (account != null) {
            initializeDrive(account)
        } else {
            viewModelScope.launch {
                _uiState.update { it.copy(driveStatus = "Connexion annulée") }
                _uiEvents.emit(MapUiEvent.ShowToast("Connexion Google Drive annulée ou échouée."))
            }
        }
    }

    private fun initializeDrive(account: GoogleSignInAccount) {
        viewModelScope.launch {
            _uiState.update { it.copy(driveStatus = "Connexion...") }
            val result = driveSyncManager.initializeWithAccount(account)
            if (result.isSuccess) {
                _uiState.update { it.copy(driveStatus = "Cloud Connecté") }
                driveSyncManager.syncAllLocalTracksToDrive()
                val imported = driveSyncManager.syncFromDrive()
                if (imported > 0) {
                    _uiEvents.emit(MapUiEvent.ShowToast("$imported trajets importés depuis Google Drive."))
                }
                _uiEvents.emit(MapUiEvent.ShowToast("Google Drive connecté et synchronisé."))
            } else {
                val exception = result.exceptionOrNull()
                if (exception is com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
                    _uiEvents.emit(MapUiEvent.LaunchSignIn(exception.intent))
                } else {
                    _uiState.update { it.copy(driveStatus = "Erreur Cloud") }
                }
            }
        }
    }

    private fun observeTrackingService() {
        viewModelScope.launch {
            LocationTrackingService.isTracking.collect { tracking ->
                _uiState.update { it.copy(isTracking = tracking) }
                if (!tracking) {
                    exportAllToPublic()
                }
            }
        }

        viewModelScope.launch {
            LocationTrackingService.distanceMeters.collect { dist ->
                _uiState.update { it.copy(distanceMeters = dist) }
            }
        }
    }

    private fun observeDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.getAllTracksFlow().collect { tracks ->
                val activeTrack = tracks.firstOrNull { it.isActive }
                val historicalTracks = tracks.filter { !it.isActive }

                val activeLatLngs = if (activeTrack != null) {
                    val points = trackDao.getPointsForTrack(activeTrack.id)
                    points.map { LatLng(it.latitude, it.longitude) }
                } else {
                    emptyList()
                }

                val historicalLatLngLists = mutableListOf<List<LatLng>>()
                val trackUiItems = mutableListOf<TrackItemUi>()
                var totalPts = 0
                var totalHistDist = 0.0

                val publicDir = GpxExporter.getPublicDirectory()

                for (ht in historicalTracks) {
                    totalHistDist += ht.distanceMeters
                    val pts = trackDao.getPointsForTrack(ht.id)
                    totalPts += pts.size
                    if (pts.isNotEmpty()) {
                        historicalLatLngLists.add(pts.map { LatLng(it.latitude, it.longitude) })
                    }

                    val dateFormatted = SimpleDateFormat("dd MMMM yyyy à HH:mm", Locale.getDefault()).format(Date(ht.startTime))
                    val distFormatted = if (ht.distanceMeters >= 1000) {
                        String.format(Locale.getDefault(), "%.2f km", ht.distanceMeters / 1000.0)
                    } else {
                        String.format(Locale.getDefault(), "%.0f m", ht.distanceMeters)
                    }
                    val durationMillis = (ht.endTime ?: ht.startTime) - ht.startTime
                    val minutes = durationMillis / 60000
                    val durFormatted = if (minutes >= 60) {
                        "${minutes / 60}h ${minutes % 60}m"
                    } else {
                        "${minutes} min"
                    }

                    val fileName = GpxExporter.generateFileName(ht)
                    val localFile = File(publicDir, fileName).takeIf { it.exists() }

                    trackUiItems.add(
                        TrackItemUi(
                            id = ht.id,
                            title = ht.title,
                            formattedDate = dateFormatted,
                            formattedDistance = distFormatted,
                            formattedDuration = durFormatted,
                            pointsCount = pts.size,
                            isSynced = ht.isSynced,
                            fileName = fileName,
                            localFile = localFile
                        )
                    )
                }

                _uiState.update { currentState ->
                    currentState.copy(
                        totalTracksCount = tracks.size,
                        activePath = activeLatLngs,
                        historicalPaths = historicalLatLngLists,
                        totalPointsCount = totalPts,
                        totalHistoricalDistanceMeters = totalHistDist,
                        trackList = trackUiItems
                    )
                }
            }
        }
    }

    fun toggleTracking(context: Context) {
        if (_uiState.value.isTracking) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP_TRACKING
            }
            context.startService(intent)
        } else {
            startTrackingService(context)
        }
    }

    fun startTrackingService(context: Context) {
        try {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START_TRACKING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------------------
    // Review Past Tracks
    // ---------------------------------------------------------------------

    fun reviewTrack(trackId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackDao.getAllTracks().firstOrNull { it.id == trackId } ?: return@launch
            val points = trackDao.getPointsForTrack(trackId)
            if (points.isEmpty()) {
                _uiEvents.emit(MapUiEvent.ShowToast("Aucun point dans ce trajet."))
                return@launch
            }

            val latLngs = points.map { LatLng(it.latitude, it.longitude) }
            val startPt = latLngs.first()
            val endPt = latLngs.last()

            val durationSec = ((track.endTime ?: track.startTime) - track.startTime) / 1000.0
            val avgSpeedKmh = if (durationSec > 10) (track.distanceMeters / durationSec) * 3.6 else 0.0
            val maxSpeedMps = points.maxOfOrNull { it.speed } ?: 0f
            val maxSpeedKmh = maxSpeedMps * 3.6

            val dateFormatted = SimpleDateFormat("dd MMMM yyyy à HH:mm", Locale.getDefault()).format(Date(track.startTime))
            val distFormatted = if (track.distanceMeters >= 1000) {
                String.format(Locale.getDefault(), "%.2f km", track.distanceMeters / 1000.0)
            } else {
                String.format(Locale.getDefault(), "%.0f m", track.distanceMeters)
            }
            val durationMillis = (track.endTime ?: track.startTime) - track.startTime
            val minutes = durationMillis / 60000
            val durFormatted = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
            val fileName = GpxExporter.generateFileName(track)
            val localFile = File(GpxExporter.getPublicDirectory(), fileName).takeIf { it.exists() }

            val trackUi = TrackItemUi(
                id = track.id,
                title = track.title,
                formattedDate = dateFormatted,
                formattedDistance = distFormatted,
                formattedDuration = durFormatted,
                pointsCount = points.size,
                isSynced = track.isSynced,
                fileName = fileName,
                localFile = localFile
            )

            _uiState.update {
                it.copy(
                    reviewedTrackId = trackId,
                    reviewedTrack = trackUi,
                    reviewedTrackPoints = latLngs,
                    reviewedTrackStart = startPt,
                    reviewedTrackEnd = endPt,
                    reviewedAvgSpeedKmh = avgSpeedKmh,
                    reviewedMaxSpeedKmh = maxSpeedKmh.toDouble()
                )
            }

            // Bounding box for camera
            var minLat = points[0].latitude
            var maxLat = points[0].latitude
            var minLng = points[0].longitude
            var maxLng = points[0].longitude
            for (p in points) {
                if (p.latitude < minLat) minLat = p.latitude
                if (p.latitude > maxLat) maxLat = p.latitude
                if (p.longitude < minLng) minLng = p.longitude
                if (p.longitude > maxLng) maxLng = p.longitude
            }
            _uiEvents.emit(MapUiEvent.FocusBounds(LatLng(minLat, minLng), LatLng(maxLat, maxLng)))
        }
    }

    fun exitReviewMode() {
        _uiState.update {
            it.copy(
                reviewedTrackId = null,
                reviewedTrack = null,
                reviewedTrackPoints = emptyList(),
                reviewedTrackStart = null,
                reviewedTrackEnd = null
            )
        }
    }

    // ---------------------------------------------------------------------
    // Cleaning Past Tracks
    // ---------------------------------------------------------------------

    fun cleanTrack(trackId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackDao.getAllTracks().firstOrNull { it.id == trackId } ?: return@launch
            val points = trackDao.getPointsForTrack(trackId)
            if (points.isEmpty()) {
                _uiEvents.emit(MapUiEvent.ShowToast("Aucun point dans ce trajet."))
                return@launch
            }

            val result = TrackCleaner.cleanTrackPoints(points, track.distanceMeters)

            // Update database with cleaned points
            trackDao.deletePointsForTrack(trackId)
            trackDao.insertTrackPoints(result.cleanedPoints)

            val updatedTrack = track.copy(
                distanceMeters = result.cleanedDistanceMeters,
                isSynced = false
            )
            trackDao.updateTrack(updatedTrack)

            // Re-export public GPX file
            GpxExporter.saveGpxToFile(getApplication(), updatedTrack, result.cleanedPoints)

            // Update Drive folder if configured
            if (driveSyncManager.hasConfiguredDriveFolder()) {
                driveSyncManager.saveTrackToSafFolder(updatedTrack)
            }

            // If in review mode for this track, refresh view
            if (_uiState.value.reviewedTrackId == trackId) {
                reviewTrack(trackId)
            }

            val distSavedM = (result.originalDistanceMeters - result.cleanedDistanceMeters).coerceAtLeast(0.0)
            val distSavedStr = if (distSavedM >= 1000) {
                String.format(Locale.getDefault(), "%.2f km", distSavedM / 1000.0)
            } else {
                String.format(Locale.getDefault(), "%.0f m", distSavedM)
            }

            _uiEvents.emit(
                MapUiEvent.ShowToast(
                    "Trajet nettoyé : ${result.pointsRemoved} points retirés (-$distSavedStr corrigés)"
                )
            )
        }
    }

    fun cleanAllTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = trackDao.getAllTracks().filter { !it.isActive }
            var totalPointsRemoved = 0
            var totalTracksCleaned = 0
            var totalDistanceSaved = 0.0

            for (track in tracks) {
                val points = trackDao.getPointsForTrack(track.id)
                if (points.isNotEmpty()) {
                    val result = TrackCleaner.cleanTrackPoints(points, track.distanceMeters)
                    if (result.pointsRemoved > 0 || result.cleanedDistanceMeters != track.distanceMeters) {
                        trackDao.deletePointsForTrack(track.id)
                        trackDao.insertTrackPoints(result.cleanedPoints)

                        val updatedTrack = track.copy(
                            distanceMeters = result.cleanedDistanceMeters,
                            isSynced = false
                        )
                        trackDao.updateTrack(updatedTrack)
                        GpxExporter.saveGpxToFile(getApplication(), updatedTrack, result.cleanedPoints)

                        if (driveSyncManager.hasConfiguredDriveFolder()) {
                            driveSyncManager.saveTrackToSafFolder(updatedTrack)
                        }

                        totalPointsRemoved += result.pointsRemoved
                        totalDistanceSaved += (result.originalDistanceMeters - result.cleanedDistanceMeters).coerceAtLeast(0.0)
                        totalTracksCleaned++
                    }
                }
            }

            if (_uiState.value.reviewedTrackId != null) {
                reviewTrack(_uiState.value.reviewedTrackId!!)
            }

            val distStr = if (totalDistanceSaved >= 1000) {
                String.format(Locale.getDefault(), "%.2f km", totalDistanceSaved / 1000.0)
            } else {
                String.format(Locale.getDefault(), "%.0f m", totalDistanceSaved)
            }

            if (totalPointsRemoved > 0) {
                _uiEvents.emit(
                    MapUiEvent.ShowToast(
                        "Nettoyage complet : $totalPointsRemoved points aberrants supprimés sur $totalTracksCleaned trajets (-$distStr)"
                    )
                )
            } else {
                _uiEvents.emit(MapUiEvent.ShowToast("Tous vos trajets sont déjà propres et réalistes !"))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Delete Past Track
    // ---------------------------------------------------------------------

    fun deleteTrack(trackId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackDao.getAllTracks().firstOrNull { it.id == trackId } ?: return@launch

            // If in review mode for this track, exit review mode first
            if (_uiState.value.reviewedTrackId == trackId) {
                exitReviewMode()
            }

            // If this track is currently active/recording, stop tracking
            if (track.isActive) {
                val intent = Intent(getApplication(), LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_STOP_TRACKING
                }
                getApplication<Application>().startService(intent)
            }

            // 1. Delete points and track entity from Room database
            trackDao.deletePointsForTrack(trackId)
            trackDao.deleteTrack(trackId)

            // 2. Delete local GPX files from Documents/TousLesCheminsMenentARome and app folders
            GpxExporter.deleteGpxFiles(getApplication(), track)

            // 3. Delete from Google Drive SAF folder if configured
            if (driveSyncManager.hasConfiguredDriveFolder()) {
                driveSyncManager.deleteTrackFromSafFolder(track)
            }

            _uiEvents.emit(MapUiEvent.ShowToast("Trajet supprimé avec succès."))
        }
    }

    fun centerOnUserLocation(context: Context) {
        try {
            val fused = LocationServices.getFusedLocationProviderClient(context)
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    viewModelScope.launch {
                        _uiEvents.emit(MapUiEvent.FocusCamera(LatLng(loc.latitude, loc.longitude), 16f))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------------------
    // Google Drive SAF Folder Handling
    // ---------------------------------------------------------------------

    fun onDriveFolderSelected(treeUri: Uri) {
        driveSyncManager.setDriveFolderUri(treeUri)
        refreshDriveFolderState()
        val name = driveSyncManager.getDriveFolderName() ?: "Google Drive"
        viewModelScope.launch(Dispatchers.IO) {
            val count = driveSyncManager.saveAllTracksToSafFolder()
            _uiEvents.emit(MapUiEvent.ShowToast("Dossier configuré : $name ($count trajets sauvegardés)"))
        }
    }

    fun pickDriveFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        viewModelScope.launch {
            _uiEvents.emit(MapUiEvent.LaunchFolderPicker(intent))
        }
    }

    fun saveAllToGoogleDrive() {
        viewModelScope.launch(Dispatchers.IO) {
            if (driveSyncManager.hasConfiguredDriveFolder()) {
                val savedCount = driveSyncManager.saveAllTracksToSafFolder()
                val folderName = driveSyncManager.getDriveFolderName() ?: "Google Drive"
                _uiEvents.emit(MapUiEvent.ShowToast("$savedCount trajets enregistrés dans : $folderName"))
            } else {
                _uiEvents.emit(MapUiEvent.ShowToast("Veuillez sélectionner le dossier Google Drive de votre choix."))
                pickDriveFolder()
            }
        }
    }

    fun requestSaveTrackToDrive(trackId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackDao.getAllTracks().firstOrNull { it.id == trackId } ?: return@launch
            val fileName = GpxExporter.generateFileName(track)
            _uiState.update { it.copy(pendingExportTrackId = trackId) }
            _uiEvents.emit(MapUiEvent.LaunchCreateDocument(fileName, trackId))
        }
    }

    fun onDocumentCreated(destinationUri: Uri) {
        val trackId = _uiState.value.pendingExportTrackId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackDao.getAllTracks().firstOrNull { it.id == trackId } ?: return@launch
            val success = driveSyncManager.writeGpxToUri(destinationUri, track)
            _uiState.update { it.copy(pendingExportTrackId = null) }
            if (success) {
                trackDao.updateTrack(track.copy(isSynced = true))
                _uiEvents.emit(MapUiEvent.ShowToast("Trajet sauvegardé avec succès dans Google Drive !"))
            } else {
                _uiEvents.emit(MapUiEvent.ShowToast("Erreur lors de l'enregistrement."))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Local Public Documents Handling
    // ---------------------------------------------------------------------

    fun exportAllToPublic() {
        performAutomaticMigration()
        viewModelScope.launch(Dispatchers.IO) {
            _uiEvents.emit(
                MapUiEvent.ShowToast(
                    "Fichiers GPX synchronisés dans Documents/${GpxExporter.FOLDER_NAME}"
                )
            )
        }
    }

    fun shareTrack(trackId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackDao.getAllTracks().firstOrNull { it.id == trackId } ?: return@launch
            val points = trackDao.getPointsForTrack(track.id)
            if (points.isEmpty()) {
                _uiEvents.emit(MapUiEvent.ShowToast("Aucun point dans ce trajet."))
                return@launch
            }
            val file = GpxExporter.saveGpxToFile(getApplication(), track, points)
            val shareIntent = driveSyncManager.createShareIntent(file)
            _uiEvents.emit(MapUiEvent.ShareGpxFile(shareIntent))
        }
    }

    fun openPublicFolder() {
        val intent = driveSyncManager.getOpenLocalFolderIntent()
        viewModelScope.launch {
            _uiEvents.emit(MapUiEvent.OpenFolder(intent))
        }
    }

    fun copyPublicFolderPath() {
        val path = GpxExporter.getPublicDirectory().absolutePath
        viewModelScope.launch {
            _uiEvents.emit(MapUiEvent.CopyToClipboard(path))
            _uiEvents.emit(MapUiEvent.ShowToast("Chemin copié : Documents/${GpxExporter.FOLDER_NAME}"))
        }
    }

    fun focusOnTrack(trackId: Long) {
        reviewTrack(trackId)
    }

    fun syncToGoogleDrive() {
        if (driveSyncManager.hasConfiguredDriveFolder()) {
            saveAllToGoogleDrive()
        } else if (_uiState.value.driveStatus == "Cloud Connecté") {
            viewModelScope.launch(Dispatchers.IO) {
                val intent = driveSyncManager.getDriveFolderIntent()
                _uiEvents.emit(MapUiEvent.ShareGpxFile(intent))
            }
        } else {
            pickDriveFolder()
        }
    }
}
