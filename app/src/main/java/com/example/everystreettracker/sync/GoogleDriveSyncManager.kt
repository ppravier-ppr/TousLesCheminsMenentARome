package com.example.everystreettracker.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.example.everystreettracker.data.db.AppDatabase
import com.example.everystreettracker.data.db.TrackEntity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections

class GoogleDriveSyncManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val trackDao = db.trackDao()
    private val prefs = context.getSharedPreferences("google_drive_sync_prefs", Context.MODE_PRIVATE)

    private var driveService: Drive? = null
    private var folderId: String? = null

    companion object {
        private const val KEY_DRIVE_TREE_URI = "drive_tree_uri"
        private const val KEY_DRIVE_FOLDER_NAME = "drive_folder_name"
    }

    // -------------------------------------------------------------
    // 1. Storage Access Framework (SAF) Google Drive Folder Management
    // -------------------------------------------------------------

    fun setDriveFolderUri(treeUri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
        val folderName = documentFile?.name ?: "Dossier sélectionné"

        prefs.edit()
            .putString(KEY_DRIVE_TREE_URI, treeUri.toString())
            .putString(KEY_DRIVE_FOLDER_NAME, folderName)
            .apply()
    }

    fun getDriveFolderUri(): Uri? {
        val uriStr = prefs.getString(KEY_DRIVE_TREE_URI, null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            null
        }
    }

    fun getDriveFolderName(): String? {
        return prefs.getString(KEY_DRIVE_FOLDER_NAME, null)
    }

    fun clearDriveFolder() {
        prefs.edit()
            .remove(KEY_DRIVE_TREE_URI)
            .remove(KEY_DRIVE_FOLDER_NAME)
            .apply()
    }

    fun hasConfiguredDriveFolder(): Boolean {
        val uri = getDriveFolderUri() ?: return false
        val doc = DocumentFile.fromTreeUri(context, uri)
        return doc != null && doc.canWrite()
    }

    suspend fun saveTrackToSafFolder(track: TrackEntity): Boolean = withContext(Dispatchers.IO) {
        val treeUri = getDriveFolderUri() ?: return@withContext false
        val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
        if (!rootFolder.canWrite()) return@withContext false

        val points = trackDao.getPointsForTrack(track.id)
        if (points.isEmpty()) return@withContext false

        val gpxContent = GpxExporter.exportToGpx(track, points)
        val fileName = GpxExporter.generateFileName(track)

        // If file already exists in folder, reuse or delete
        val existing = rootFolder.findFile(fileName)
        val targetDoc = existing ?: rootFolder.createFile("application/gpx+xml", fileName)
            ?: return@withContext false

        try {
            context.contentResolver.openOutputStream(targetDoc.uri)?.use { os ->
                os.write(gpxContent.toByteArray(Charsets.UTF_8))
            }
            trackDao.updateTrack(track.copy(isSynced = true))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deleteTrackFromSafFolder(track: TrackEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val treeUri = getDriveFolderUri() ?: return@withContext false
            val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
            val fileName = GpxExporter.generateFileName(track)
            val existing = rootFolder.findFile(fileName)
            existing?.delete() ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun saveAllTracksToSafFolder(): Int = withContext(Dispatchers.IO) {
        val tracks = trackDao.getAllTracks()
        var savedCount = 0
        for (track in tracks) {
            if (saveTrackToSafFolder(track)) {
                savedCount++
            }
        }
        savedCount
    }

    fun writeGpxToUri(destinationUri: Uri, track: TrackEntity): Boolean {
        return try {
            val points = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                trackDao.getPointsForTrack(track.id)
            }
            val gpxContent = GpxExporter.exportToGpx(track, points)
            context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                os.write(gpxContent.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // -------------------------------------------------------------
    // 2. Intents for User Actions (Open Folder, Save As, Share)
    // -------------------------------------------------------------

    fun createShareIntent(gpxFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, gpxFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Parcours GPX - ${gpxFile.name}")
            putExtra(Intent.EXTRA_TEXT, "Fichier GPX enregistré par TousLesCheminsMenentARome.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Partager / Enregistrer dans Google Drive")
    }

    fun createSaveFileToDriveIntent(fileName: String): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
    }

    fun getOpenLocalFolderIntent(): Intent {
        val uri = Uri.parse(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath + "/" + GpxExporter.FOLDER_NAME
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun getLocalFolderPath(): String {
        return GpxExporter.getPublicDirectory().absolutePath
    }

    // -------------------------------------------------------------
    // 3. Optional Google Drive REST API Integration (OAuth2)
    // -------------------------------------------------------------

    fun getSignInIntent(): Intent {
        val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        return GoogleSignIn.getClient(context, gso).signInIntent
    }

    suspend fun initializeWithAccount(account: GoogleSignInAccount): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account

            driveService = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory(),
                credential
            ).setApplicationName(GpxExporter.FOLDER_NAME).build()

            val id = getOrCreateFolder(GpxExporter.FOLDER_NAME)
            if (id != null) {
                folderId = id
                Result.success(true)
            } else {
                Result.failure(Exception("Accès refusé ou dossier introuvable. Vérifiez les permissions Drive API."))
            }
        } catch (e: UserRecoverableAuthIOException) {
            Result.failure(e)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun getOrCreateFolder(folderName: String): String? {
        val service = driveService ?: return null
        try {
            val result: FileList = service.files().list()
                .setQ("name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setSpaces("drive")
                .execute()

            if (result.files != null && result.files.isNotEmpty()) {
                return result.files[0].id
            }

            val folderMetadata = DriveFile().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
            }
            val folder = service.files().create(folderMetadata)
                .setFields("id")
                .execute()
            return folder.id
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    suspend fun uploadTrack(file: File): String? = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext null
        val parentId = folderId ?: return@withContext null

        val fileMetadata = DriveFile().apply {
            name = file.name
            parents = listOf(parentId)
        }

        val mediaContent = com.google.api.client.http.FileContent("application/gpx+xml", file)
        val driveFile = service.files().create(fileMetadata, mediaContent)
            .setFields("id, webViewLink")
            .execute()

        driveFile.webViewLink
    }

    suspend fun syncAllLocalTracksToDrive(): List<String> = withContext(Dispatchers.IO) {
        val tracks = trackDao.getAllTracks()
        val links = mutableListOf<String>()
        for (track in tracks) {
            if (!track.isSynced) {
                val points = trackDao.getPointsForTrack(track.id)
                if (points.isNotEmpty()) {
                    val file = GpxExporter.saveGpxToFile(context, track, points)
                    val link = uploadTrack(file)
                    if (link != null) {
                        trackDao.updateTrack(track.copy(isSynced = true))
                        links.add(link)
                    }
                }
            }
        }
        links
    }

    suspend fun syncFromDrive(): Int = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext 0
        val parentId = folderId ?: return@withContext 0

        val result: FileList = service.files().list()
            .setQ("'$parentId' in parents and mimeType = 'application/gpx+xml' and trashed = false")
            .setFields("files(id, name)")
            .execute()

        var importedCount = 0
        val localTracks = trackDao.getAllTracks()

        for (driveFile in result.files) {
            if (localTracks.none { it.title == driveFile.name.substringBeforeLast(".gpx") }) {
                try {
                    val outputStream = ByteArrayOutputStream()
                    service.files().get(driveFile.id).executeMediaAndDownloadTo(outputStream)
                    val (track, points) = GpxExporter.parseGpx(outputStream.toByteArray().inputStream())

                    val newTrackId = trackDao.insertTrack(track.copy(title = driveFile.name.substringBeforeLast(".gpx")))
                    points.forEach { trackDao.insertTrackPoint(it.copy(trackId = newTrackId)) }
                    importedCount++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        importedCount
    }

    fun getDriveFolderIntent(): Intent {
        val url = if (folderId != null) {
            "https://drive.google.com/drive/folders/$folderId"
        } else {
            "https://drive.google.com/"
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }
}
