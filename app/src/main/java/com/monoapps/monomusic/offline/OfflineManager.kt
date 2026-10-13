package com.monoapps.monomusic.offline

import android.content.Context
import android.os.Environment
import com.monoapps.monomusic.tidal.AudioQuality
import com.monoapps.monomusic.tidal.TidalApiException
import com.monoapps.monomusic.tidal.TidalClient
import com.monoapps.monomusic.tidal.TidalSession
import com.monoapps.monomusic.tidal.TidalTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An album or playlist the user pinned for offline playback. */
data class OfflineCollection(
    val type: String, // "album" or "playlist"
    val id: String,
    val title: String,
    val subtitle: String?,
    val quality: AudioQuality,
    val tracks: List<TidalTrack>,
) {
    val key: String get() = "$type:$id"
}

/**
 * Pinned-offline downloads. Audio files live in app-private storage,
 * preferring the SD card when one is mounted; the index stays on internal
 * storage so it survives card swaps. Everything plays through the normal
 * tidal:// queue URIs; the playback service checks [fileFor] first.
 */
class OfflineManager(
    context: Context,
    private val client: TidalClient,
    private val session: TidalSession,
    private val http: OkHttpClient,
) {
    companion object {
        /** Free space to keep on the download volume; the worker pauses below it. */
        const val MIN_FREE_BYTES = 500L * 1024 * 1024

        /** Rough stream byte rates used for the size estimate in the pin dialog. */
        fun estimatedBytes(totalSeconds: Long, quality: AudioQuality): Long {
            val bytesPerSecond = when (quality) {
                AudioQuality.LOW -> 12_000L      // 96 kbps AAC
                AudioQuality.HIGH -> 40_000L     // 320 kbps AAC
                AudioQuality.LOSSLESS -> 100_000L // FLAC, varies a lot
            }
            return totalSeconds * bytesPerSecond
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
            else -> "%.0f kB".format(bytes / 1_000.0)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val indexFile = File(context.filesDir, "offline_index.json")

    val audioDir: File
    val onRemovableStorage: Boolean

    // Active location first, then the internal fallback: files downloaded
    // before an SD card was inserted keep playing from internal storage and
    // are cleaned on unpin instead of leaking there forever.
    private val searchDirs: List<File>

    init {
        val removable = context.getExternalFilesDirs(null).filterNotNull().firstOrNull { dir ->
            runCatching { Environment.isExternalStorageRemovable(dir) }.getOrDefault(false)
        }
        onRemovableStorage = removable != null
        audioDir = File(removable ?: context.filesDir, "offline").apply { mkdirs() }
        val internal = File(context.filesDir, "offline")
        searchDirs = if (internal.path == audioDir.path) listOf(audioDir)
        else listOf(audioDir, internal)
    }

    /** Free space on the volume new downloads go to. */
    val freeBytes: Long get() = audioDir.usableSpace

    val collections = MutableStateFlow<List<OfflineCollection>>(emptyList())
    val downloadedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingCount = MutableStateFlow(0)
    val sizeBytes = MutableStateFlow(0L)

    /** True while downloads are paused because the volume is nearly full. */
    val storageLow = MutableStateFlow(false)

    // trackId -> (fileName, quality); volatile snapshot for the playback thread
    @Volatile
    private var fileEntries = mapOf<Long, Pair<String, AudioQuality>>()

    private var worker: Job? = null
    private val failedThisRun = mutableSetOf<Long>()

    init {
        scope.launch {
            mutex.withLock { load() }
            ensureDownloads()
        }
    }

    /** The local audio file for a track, if it was downloaded and still exists. */
    fun fileFor(trackId: Long): File? {
        val name = fileEntries[trackId]?.first ?: return null
        return searchDirs.firstNotNullOfOrNull { dir -> File(dir, name).takeIf { it.exists() } }
    }

    fun qualityFor(trackId: Long): AudioQuality? = fileEntries[trackId]?.second

    fun pin(collection: OfflineCollection) {
        // Stamp pin time as date-added, unless the track already has one
        // (e.g. a favorite keeps the day it was favorited).
        val now = System.currentTimeMillis()
        val stamped = collection.copy(
            tracks = collection.tracks.map { if (it.addedAt > 0) it else it.copy(addedAt = now) }
        )
        scope.launch {
            mutex.withLock {
                collections.value =
                    collections.value.filterNot { it.key == stamped.key } + stamped
                save()
                refreshDerivedState()
            }
            ensureDownloads()
        }
    }

    fun unpin(type: String, id: String) {
        scope.launch {
            mutex.withLock {
                collections.value = collections.value.filterNot { it.key == "$type:$id" }
                deleteOrphanFiles()
                save()
                refreshDerivedState()
            }
            // Freed space may unblock a storage-low pause.
            ensureDownloads()
        }
    }

    fun removeAll() {
        scope.launch {
            mutex.withLock {
                collections.value = emptyList()
                deleteOrphanFiles()
                save()
                refreshDerivedState()
            }
            ensureDownloads()
        }
    }

    /** Starts the download worker if there is anything missing; no-op otherwise. */
    @Synchronized
    fun ensureDownloads() {
        if (!session.isLoggedIn) return
        if (worker?.isActive == true) return
        worker = scope.launch { downloadLoop() }
    }

    private suspend fun downloadLoop() {
        failedThisRun.clear()
        storageLow.value = false
        var backoffSec = 5L
        while (true) {
            val next = mutex.withLock {
                collections.value
                    .flatMap { c -> c.tracks.map { it to c.quality } }
                    .firstOrNull { (track, _) ->
                        track.id !in failedThisRun && fileFor(track.id) == null
                    }
            } ?: break
            if (audioDir.usableSpace < MIN_FREE_BYTES) {
                // Stop rather than fill the volume to the brim; unpinning or
                // freeing space restarts the worker.
                storageLow.value = true
                break
            }
            val (track, quality) = next
            try {
                download(track, quality)
                backoffSec = 5L
            } catch (e: TidalApiException) {
                if (e.httpCode == 429) {
                    // Rate limited (large playlists trip this). Wait it out
                    // and retry the same track; failing it would cascade the
                    // whole remaining queue into doomed, limiter-feeding
                    // requests.
                    delay(backoffSec * 1000)
                    backoffSec = (backoffSec * 2).coerceAtMost(300)
                } else {
                    failedThisRun += track.id
                }
            } catch (e: Exception) {
                failedThisRun += track.id
            }
            mutex.withLock { refreshDerivedState() }
        }
    }

    private suspend fun download(track: TidalTrack, quality: AudioQuality) {
        val info = client.streamInfo(track.id, quality)
        val extension = if (info.codec == "flac") "flac" else "m4a"
        val name = "${track.id}.$extension"
        val tmp = File(audioDir, "$name.part")
        http.newCall(Request.Builder().url(info.url).build()).execute().use { response ->
            if (!response.isSuccessful) error("Download failed (HTTP ${response.code})")
            response.body!!.byteStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!tmp.renameTo(File(audioDir, name))) error("Could not move download into place")
        mutex.withLock {
            fileEntries = fileEntries + (track.id to (name to quality))
            save()
        }
    }

    private fun deleteOrphanFiles() {
        val wanted = collections.value.flatMap { it.tracks }.map { it.id }.toSet()
        fileEntries.filterKeys { it !in wanted }.forEach { (id, entry) ->
            searchDirs.forEach { dir ->
                File(dir, entry.first).delete()
                File(dir, "${entry.first}.part").delete()
            }
        }
        fileEntries = fileEntries.filterKeys { it in wanted }
    }

    private fun refreshDerivedState() {
        val pinnedIds = collections.value.flatMap { it.tracks }.map { it.id }.toSet()
        val downloaded = pinnedIds.filter { fileFor(it) != null }.toSet()
        downloadedTrackIds.value = downloaded
        pendingCount.value = pinnedIds.size - downloaded.size
        // Always runs on the IO scope, so the directory walk stays off the
        // main thread; the UI reads the flow instead of listing files itself.
        sizeBytes.value = searchDirs.sumOf { dir ->
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }

    // ---- persistence (org.json, same as the API layer) ----

    private fun load() {
        if (!indexFile.exists()) return
        runCatching {
            val root = JSONObject(indexFile.readText())
            fileEntries = root.optJSONArray("files").orEmpty().mapNotNull { o ->
                val quality = AudioQuality.entries
                    .firstOrNull { it.name == o.optString("quality") } ?: return@mapNotNull null
                o.optLong("id") to (o.optString("name") to quality)
            }.toMap()
            collections.value = root.optJSONArray("collections").orEmpty().mapNotNull { o ->
                val quality = AudioQuality.entries
                    .firstOrNull { it.name == o.optString("quality") } ?: return@mapNotNull null
                OfflineCollection(
                    type = o.optString("type"),
                    id = o.optString("id"),
                    title = o.optString("title"),
                    subtitle = o.optString("subtitle").takeIf { it.isNotEmpty() },
                    quality = quality,
                    tracks = o.optJSONArray("tracks").orEmpty().map { t ->
                        TidalTrack(
                            id = t.optLong("id"),
                            title = t.optString("title"),
                            artistName = t.optString("artist"),
                            artistId = t.optLong("artistId").takeIf { it > 0 },
                            albumTitle = t.optString("album").takeIf { it.isNotEmpty() },
                            albumId = t.optLong("albumId").takeIf { it > 0 },
                            durationSec = t.optInt("duration"),
                            trackNumber = t.optInt("trackNumber"),
                            volumeNumber = t.optInt("volumeNumber", 1),
                            explicit = false,
                            audioQuality = null,
                            addedAt = t.optLong("addedAt"),
                        )
                    },
                )
            }
            refreshDerivedState()
        }
    }

    private fun save() {
        val root = JSONObject()
        root.put("files", JSONArray().apply {
            fileEntries.forEach { (id, entry) ->
                put(
                    JSONObject()
                        .put("id", id)
                        .put("name", entry.first)
                        .put("quality", entry.second.name)
                )
            }
        })
        root.put("collections", JSONArray().apply {
            collections.value.forEach { c ->
                put(
                    JSONObject()
                        .put("type", c.type)
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("subtitle", c.subtitle ?: "")
                        .put("quality", c.quality.name)
                        .put("tracks", JSONArray().apply {
                            c.tracks.forEach { t ->
                                put(
                                    JSONObject()
                                        .put("id", t.id)
                                        .put("title", t.title)
                                        .put("artist", t.artistName)
                                        .putOpt("artistId", t.artistId)
                                        .put("album", t.albumTitle ?: "")
                                        .putOpt("albumId", t.albumId)
                                        .put("duration", t.durationSec)
                                        .put("trackNumber", t.trackNumber)
                                        .put("volumeNumber", t.volumeNumber)
                                        .put("addedAt", t.addedAt)
                                )
                            }
                        })
                )
            }
        })
        indexFile.writeText(root.toString())
    }

    private fun JSONArray?.orEmpty(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
