package com.example.everystreettracker.sync

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Xml
import com.example.everystreettracker.data.db.TrackEntity
import com.example.everystreettracker.data.db.TrackPointEntity
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GpxExporter {

    const val FOLDER_NAME = "TousLesCheminsMenentARome"

    private val iso8601Format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun getPublicDirectory(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docsDir, FOLDER_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getDisplayFolderPath(): String {
        return "Stockage interne > Documents > $FOLDER_NAME"
    }

    fun generateFileName(track: TrackEntity): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.getDefault()).format(Date(track.startTime))
        val distanceStr = if (track.distanceMeters >= 1000) {
            String.format(Locale.getDefault(), "%.1fkm", track.distanceMeters / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%.0fm", track.distanceMeters)
        }
        val durationMillis = (track.endTime ?: System.currentTimeMillis()) - track.startTime
        val hours = durationMillis / 3600000
        val minutes = (durationMillis % 3600000) / 60000
        val durationStr = String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)

        return "Trajet_${dateStr}_${distanceStr}_${durationStr}.gpx"
    }

    fun exportToGpx(track: TrackEntity, points: List<TrackPointEntity>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"TousLesCheminsMenentARome Android App\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(track.title)).append("</name>\n")
        sb.append("    <time>").append(iso8601Format.format(Date(track.startTime))).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(track.title)).append("</name>\n")
        sb.append("    <trkseg>\n")

        for (pt in points) {
            sb.append("      <trkpt lat=\"").append(pt.latitude).append("\" lon=\"").append(pt.longitude).append("\">\n")
            if (pt.altitude != 0.0) {
                sb.append("        <ele>").append(pt.altitude).append("</ele>\n")
            }
            sb.append("        <time>").append(iso8601Format.format(Date(pt.timestamp))).append("</time>\n")
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")

        return sb.toString()
    }

    /**
     * Saves the GPX file into the user's public Documents/TousLesCheminsMenentARome folder.
     * This location is directly browsable in the phone's "Files" / "Mes Fichiers" app.
     */
    fun saveGpxToFile(context: Context, track: TrackEntity, points: List<TrackPointEntity>): File {
        val xmlContent = exportToGpx(track, points)
        val fileName = generateFileName(track)

        // 1. Try saving to public Documents/TousLesCheminsMenentARome
        val publicDir = getPublicDirectory()
        var targetFile = File(publicDir, fileName)

        try {
            targetFile.writeText(xmlContent)
            // Trigger MediaScanner so the file manager detects it immediately
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf("application/gpx+xml"),
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to app external files dir
            val appDir = File(context.getExternalFilesDir(null), FOLDER_NAME)
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            targetFile = File(appDir, fileName)
            targetFile.writeText(xmlContent)
        }

        // Also save a copy in app external files dir for guaranteed access
        try {
            val appDir = File(context.getExternalFilesDir(null), FOLDER_NAME)
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            val internalFile = File(appDir, fileName)
            internalFile.writeText(xmlContent)
        } catch (ignored: Exception) {}

        return targetFile
    }

    /**
     * Deletes the GPX file from all local storage locations (public Documents and internal storage).
     */
    fun deleteGpxFiles(context: Context, track: TrackEntity): Boolean {
        var deleted = false
        val fileName = generateFileName(track)

        // 1. Delete from public documents
        val publicFile = File(getPublicDirectory(), fileName)
        if (publicFile.exists()) {
            if (publicFile.delete()) deleted = true
            MediaScannerConnection.scanFile(
                context,
                arrayOf(publicFile.absolutePath),
                null,
                null
            )
        }

        // 2. Also check app external files directory
        val appDir = File(context.getExternalFilesDir(null), FOLDER_NAME)
        val appFile = File(appDir, fileName)
        if (appFile.exists()) {
            if (appFile.delete()) deleted = true
        }

        // 3. Also check private files directory
        val privateFile = File(context.filesDir, fileName)
        if (privateFile.exists()) {
            if (privateFile.delete()) deleted = true
        }

        return deleted
    }

    fun listExportedFiles(context: Context): List<File> {
        val files = mutableListOf<File>()
        val publicDir = getPublicDirectory()
        if (publicDir.exists()) {
            publicDir.listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }?.let {
                files.addAll(it)
            }
        }
        val appDir = File(context.getExternalFilesDir(null), FOLDER_NAME)
        if (appDir.exists()) {
            appDir.listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }?.let { list ->
                for (f in list) {
                    if (files.none { it.name == f.name }) {
                        files.add(f)
                    }
                }
            }
        }
        return files.sortedByDescending { it.lastModified() }
    }

    /**
     * Checks old private app storage folders and copies all existing GPX files
     * to the public Documents/TousLesCheminsMenentARome folder.
     */
    fun migratePrivateFilesToPublic(context: Context): Int {
        val publicDir = getPublicDirectory()
        val privateFolders = listOf(
            File(context.getExternalFilesDir(null), FOLDER_NAME),
            File(context.getExternalFilesDir(null), "EveryStreet_GPX")
        )

        var migratedCount = 0
        val scannedPaths = mutableListOf<String>()

        for (folder in privateFolders) {
            if (folder.exists() && folder.isDirectory) {
                folder.listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }?.forEach { oldFile ->
                    val targetFile = File(publicDir, oldFile.name)
                    if (!targetFile.exists()) {
                        try {
                            oldFile.copyTo(targetFile, overwrite = true)
                            scannedPaths.add(targetFile.absolutePath)
                            migratedCount++
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        if (scannedPaths.isNotEmpty()) {
            MediaScannerConnection.scanFile(
                context,
                scannedPaths.toTypedArray(),
                Array(scannedPaths.size) { "application/gpx+xml" },
                null
            )
        }

        return migratedCount
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun parseGpx(inputStream: InputStream): Pair<TrackEntity, List<TrackPointEntity>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)
        
        var trackTitle = "Imported Track"
        var startTime = System.currentTimeMillis()
        val points = mutableListOf<TrackPointEntity>()
        
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "name" -> trackTitle = parser.nextText()
                        "time" -> {
                            val timeStr = parser.nextText()
                            try { startTime = iso8601Format.parse(timeStr)?.time ?: startTime } catch (e: Exception) {}
                        }
                        "trkpt" -> {
                            val lat = parser.getAttributeValue(null, "lat").toDouble()
                            val lon = parser.getAttributeValue(null, "lon").toDouble()
                            var alt = 0.0
                            var ptTime = System.currentTimeMillis()
                            
                            var innerEvent = parser.next()
                            while (!(innerEvent == XmlPullParser.END_TAG && parser.name == "trkpt")) {
                                if (innerEvent == XmlPullParser.START_TAG) {
                                    if (parser.name == "ele") alt = parser.nextText().toDouble()
                                    if (parser.name == "time") {
                                        val t = parser.nextText()
                                        try { ptTime = iso8601Format.parse(t)?.time ?: ptTime } catch (e: Exception) {}
                                    }
                                }
                                innerEvent = parser.next()
                            }
                            points.add(TrackPointEntity(trackId = 0, latitude = lat, longitude = lon, altitude = alt, timestamp = ptTime))
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        
        val track = TrackEntity(title = trackTitle, startTime = startTime, isActive = false, isSynced = true)
        return Pair(track, points)
    }
}
