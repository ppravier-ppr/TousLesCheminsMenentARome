package com.example.everystreettracker.ui.map

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.everystreettracker.BuildConfig
import com.example.everystreettracker.service.LocationTrackingService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by LocationTrackingService.currentLocation.collectAsState()

    var showTripsSheet by remember { mutableStateOf(false) }
    var trackToDelete by remember { mutableStateOf<TrackItemUi?>(null) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(48.8566, 2.3522), 14f) // Paris default
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
        if (granted) {
            viewModel.centerOnUserLocation(context)
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            viewModel.onSignInResult(account)
        } catch (e: Exception) {
            viewModel.onSignInResult(null)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.onDriveFolderSelected(uri)
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        if (uri != null) {
            viewModel.onDocumentCreated(uri)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )
        } else {
            viewModel.centerOnUserLocation(context)
        }
    }

    LaunchedEffect(currentLocation) {
        currentLocation?.let { loc ->
            val userLatLng = LatLng(loc.latitude, loc.longitude)
            if (uiState.activePath.size <= 2) {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(userLatLng, 16f))
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is MapUiEvent.ShareGpxFile -> {
                    context.startActivity(event.chooserIntent)
                }
                is MapUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is MapUiEvent.LaunchSignIn -> {
                    signInLauncher.launch(event.intent)
                }
                is MapUiEvent.LaunchFolderPicker -> {
                    folderPickerLauncher.launch(null)
                }
                is MapUiEvent.LaunchCreateDocument -> {
                    createDocumentLauncher.launch(event.defaultFileName)
                }
                is MapUiEvent.FocusCamera -> {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(event.target, event.zoom))
                }
                is MapUiEvent.FocusBounds -> {
                    try {
                        val bounds = LatLngBounds(event.southwest, event.northeast)
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                    } catch (e: Exception) {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(event.southwest, 15f))
                    }
                }
                is MapUiEvent.OpenFolder -> {
                    try {
                        context.startActivity(event.intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Dossier : ${uiState.publicFolderPath}", Toast.LENGTH_LONG).show()
                    }
                }
                is MapUiEvent.OpenUrl -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                    context.startActivity(intent)
                }
                is MapUiEvent.CopyToClipboard -> {
                    clipboardManager.setText(AnnotatedString(event.text))
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Google Map Background
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = hasLocationPermission,
                mapType = MapType.NORMAL
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = true,
                myLocationButtonEnabled = true,
                compassEnabled = true,
                mapToolbarEnabled = false
            ),
            contentPadding = PaddingValues(top = 170.dp, bottom = 120.dp)
        ) {
            // Draw Historical Traversed Paths (Teal / Green translucent line for visited streets)
            for (historicalTrack in uiState.historicalPaths) {
                if (historicalTrack.size >= 2) {
                    Polyline(
                        points = historicalTrack,
                        color = Color(0xCC00BFA5),
                        width = 12f
                    )
                }
            }

            // Draw Current Active Path (Vibrant Coral/Red line)
            if (uiState.activePath.size >= 2) {
                Polyline(
                    points = uiState.activePath,
                    color = Color(0xFFFF1744),
                    width = 16f
                )
            }

            // Draw Reviewed Track (Electric Cyan line with high z-index and markers)
            if (uiState.reviewedTrackPoints.size >= 2) {
                Polyline(
                    points = uiState.reviewedTrackPoints,
                    color = Color(0xFF00E5FF),
                    width = 18f,
                    zIndex = 10f
                )
            }

            uiState.reviewedTrackStart?.let { startPt ->
                Marker(
                    state = rememberMarkerState(
                        key = "start_${uiState.reviewedTrackId}_${startPt.latitude}_${startPt.longitude}",
                        position = startPt
                    ),
                    title = "🚩 Départ",
                    snippet = "Début du parcours"
                )
            }

            uiState.reviewedTrackEnd?.let { endPt ->
                if (endPt != uiState.reviewedTrackStart) {
                    Marker(
                        state = rememberMarkerState(
                            key = "end_${uiState.reviewedTrackId}_${endPt.latitude}_${endPt.longitude}",
                            position = endPt
                        ),
                        title = "🏁 Arrivée",
                        snippet = "Fin du parcours"
                    )
                }
            }
        }

        // Top Status Header Card: Review Mode or Normal Tracking Status
        if (uiState.reviewedTrackId != null && uiState.reviewedTrack != null) {
            val reviewed = uiState.reviewedTrack!!
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color(0xFF00E5FF), shape = CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "🔍 Revue : ${reviewed.formattedDate}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        IconButton(
                            onClick = { viewModel.exitReviewMode() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text(text = "✕", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Distance", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = reviewed.formattedDistance,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Durée", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = reviewed.formattedDuration,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Vit. Moyenne", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = String.format(Locale.getDefault(), "%.1f km/h", uiState.reviewedAvgSpeedKmh),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F766E)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Vit. Max", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = String.format(Locale.getDefault(), "%.1f km/h", uiState.reviewedMaxSpeedKmh),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFEA580C)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { viewModel.cleanTrack(reviewed.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(38.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(
                                text = "🧹 Nettoyer",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.shareTrack(reviewed.id) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(text = "📤", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { trackToDelete = reviewed },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(text = "🗑️", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { viewModel.exitReviewMode() },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(text = "Quitter", fontSize = 11.sp)
                        }
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(
                                        if (uiState.isTracking) Color(0xFF00E676) else Color(0xFFFF5252),
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isTracking) "Enregistrement actif" else "Suivi en pause",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        // Clickable Trips Badge
                        Surface(
                            color = Color(0xFF00BFA5).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable { showTripsSheet = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "📂 ${uiState.totalTracksCount} trajets",
                                    color = Color(0xFF00796B),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Total Cumulé", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = formatDistance(uiState.totalHistoricalDistanceMeters + uiState.distanceMeters),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .width(1.dp)
                                .background(Color.LightGray)
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Trajet actuel", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = formatDistance(uiState.distanceMeters),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }

        // Bottom Controls Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Toggle Tracking Button
                Button(
                    onClick = { viewModel.toggleTracking(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isTracking) Color(0xFFFF3D00) else Color(0xFF2979FF)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text(
                        text = if (uiState.isTracking) "Pause" else "Démarrer",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Trips & Files Button
                Button(
                    onClick = { showTripsSheet = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(
                        text = "Mes Trajets 📂",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Google Drive Sync Button
                Button(
                    onClick = { viewModel.syncToGoogleDrive() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F9D58)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(
                        text = "Drive ☁️",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Modal Bottom Sheet: Mes Trajets & Fichiers
        if (showTripsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showTripsSheet = false },
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 30.dp)
                ) {
                    // Sheet Title
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📂 Mes Trajets & Fichiers",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        IconButton(onClick = { showTripsSheet = false }) {
                            Text(text = "✕", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 1. Local Files Information Card
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "📁 Fichiers sur votre téléphone :",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF1E293B)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.publicFolderPath,
                                fontSize = 12.sp,
                                color = Color(0xFF0F766E),
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Vos fichiers .gpx sont accessibles dans l'application 'Fichiers' de votre téléphone (dossier Documents).",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.copyPublicFolderPath() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(text = "📋 Copier chemin", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.exportAllToPublic() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(text = "🔄 Actualiser GPX", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Google Drive Configuration Card
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "☁️ Sauvegarde Google Drive :",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF1B5E20)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            if (uiState.hasDriveFolder && uiState.driveFolderName != null) {
                                Text(
                                    text = "✅ Dossier cible : ${uiState.driveFolderName}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = "⚠️ Aucun dossier Google Drive sélectionné",
                                    fontSize = 12.sp,
                                    color = Color(0xFFC62828),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Sélectionnez un dossier sur votre Drive pour y synchroniser automatiquement tous vos parcours.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF558B2F)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.pickDriveFolder() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F9D58)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (uiState.hasDriveFolder) "Changer de dossier" else "Choisir dossier Drive 📂",
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                }
                                if (uiState.hasDriveFolder) {
                                    OutlinedButton(
                                        onClick = { viewModel.saveAllToGoogleDrive() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(text = "Tout synchroniser ☁️", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3. List of Past Tracks
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Vos Parcours Enregistrés (${uiState.trackList.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (uiState.trackList.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { viewModel.cleanAllTracks() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "🧹 Tout nettoyer",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD97706)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (uiState.trackList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Aucun trajet passé pour le moment.\nVos parcours apparaîtront ici dès que vous stopperez un enregistrement.",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                        ) {
                            items(uiState.trackList) { track ->
                                TrackCard(
                                    track = track,
                                    onShare = { viewModel.shareTrack(track.id) },
                                    onSaveToDrive = { viewModel.requestSaveTrackToDrive(track.id) },
                                    onReview = {
                                        viewModel.reviewTrack(track.id)
                                        showTripsSheet = false
                                    },
                                    onClean = { viewModel.cleanTrack(track.id) },
                                    onDelete = { trackToDelete = track }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Delete Confirmation Dialog
        if (trackToDelete != null) {
            val track = trackToDelete!!
            AlertDialog(
                onDismissRequest = { trackToDelete = null },
                title = {
                    Text(
                        text = "Supprimer ce trajet ?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "Voulez-vous vraiment supprimer définitivement le parcours du ${track.formattedDate} (${track.formattedDistance}) ?\n\nCette action supprimera le tracé de la carte ainsi que le fichier GPX associé.",
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteTrack(track.id)
                            trackToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Text("Supprimer", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { trackToDelete = null }) {
                        Text("Annuler")
                    }
                }
            )
        }
    }
}

@Composable
fun TrackCard(
    track: TrackItemUi,
    onShare: () -> Unit,
    onSaveToDrive: () -> Unit,
    onReview: () -> Unit,
    onClean: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.formattedDate,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (track.isSynced) Color(0xFFE8F5E9) else Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (track.isSynced) "☁️ Sur Drive" else "📁 Local",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (track.isSynced) Color(0xFF2E7D32) else Color(0xFF64748B),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Text(text = "🗑️", fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Distance : ${track.formattedDistance}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Durée : ${track.formattedDuration}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = "${track.pointsCount} points",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "📄 ${track.fileName}",
                fontSize = 10.sp,
                color = Color.DarkGray,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons in 2 balanced rows
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = onReview,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "🗺️ Revoir", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onClean,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "🧹 Nettoyer",
                        fontSize = 11.sp,
                        color = Color(0xFFD97706),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onShare,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "📤 Partager", fontSize = 11.sp)
                }
                FilledTonalButton(
                    onClick = onSaveToDrive,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "☁️ Sauvegarder", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "🗑️", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
    } else {
        String.format(Locale.getDefault(), "%.0f m", meters)
    }
}
