package com.monoapps.monomusic.tidal

import android.content.Context
import com.monoapps.monomusic.tidal.TidalJson.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Disk snapshot of the library tabs so a cold start shows them instantly
 * while the network refresh runs. Lists are stored in the same JSON shape
 * the Tidal API uses, so [TidalJson]'s parsers read them back.
 */
class LibraryCache(context: Context) {

    data class Snapshot(
        val tracks: List<TidalTrack>?,
        val albums: List<TidalAlbum>?,
        val artists: List<TidalArtist>?,
        val playlists: List<TidalPlaylist>?,
    )

    private val dir = File(context.filesDir, "library_cache")

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) {
        Snapshot(
            tracks = read("tracks.json")?.mapNotNull(TidalJson::parseTrack),
            albums = read("albums.json")?.mapNotNull(TidalJson::parseAlbum),
            artists = read("artists.json")?.mapNotNull(TidalJson::parseArtist),
            playlists = read("playlists.json")?.mapNotNull(TidalJson::parsePlaylist),
        )
    }

    suspend fun saveTracks(tracks: List<TidalTrack>) = write("tracks.json", tracks, ::trackJson)

    /** Track lists of liked albums, so they can surface in the Songs tab. */
    suspend fun loadAlbumTracks(): Map<Long, List<TidalTrack>> = withContext(Dispatchers.IO) {
        val file = File(dir, "album_tracks.json")
        if (!file.exists()) return@withContext emptyMap()
        runCatching {
            val root = JSONObject(file.readText())
            buildMap {
                root.keys().forEach { key ->
                    val id = key.toLongOrNull() ?: return@forEach
                    put(id, root.getJSONArray(key).objects().mapNotNull(TidalJson::parseTrack))
                }
            }
        }.getOrDefault(emptyMap())
    }

    suspend fun saveAlbumTracks(albumTracks: Map<Long, List<TidalTrack>>) =
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val root = JSONObject()
                albumTracks.forEach { (id, tracks) ->
                    root.put(id.toString(), JSONArray().apply { tracks.forEach { put(trackJson(it)) } })
                }
                File(dir, "album_tracks.json").writeText(root.toString())
            }
            Unit
        }

    private fun trackJson(t: TidalTrack): JSONObject =
        JSONObject()
            .put("id", t.id)
            .put("title", t.title)
            .put("artist", person(t.artistName, t.artistId))
            .put(
                "album",
                JSONObject().apply {
                    t.albumTitle?.let { put("title", it) }
                    t.albumId?.let { put("id", it) }
                },
            )
            .put("duration", t.durationSec)
            .put("trackNumber", t.trackNumber)
            .put("volumeNumber", t.volumeNumber)
            .put("explicit", t.explicit)
            .putOpt("audioQuality", t.audioQuality)

    suspend fun saveAlbums(albums: List<TidalAlbum>) = write("albums.json", albums) { a ->
        JSONObject()
            .put("id", a.id)
            .put("title", a.title)
            .put("artist", person(a.artistName, a.artistId))
            .put("numberOfTracks", a.numberOfTracks)
            .putOpt("releaseDate", a.releaseYear)
            .putOpt("audioQuality", a.audioQuality)
    }

    suspend fun saveArtists(artists: List<TidalArtist>) = write("artists.json", artists) { a ->
        JSONObject().put("id", a.id).put("name", a.name)
    }

    suspend fun savePlaylists(playlists: List<TidalPlaylist>) = write("playlists.json", playlists) { p ->
        JSONObject()
            .put("uuid", p.uuid)
            .put("title", p.title)
            .put("numberOfTracks", p.numberOfTracks)
            .putOpt("description", p.description)
            .putOpt("creator", p.creator?.let { JSONObject().put("name", it) })
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun person(name: String, id: Long?): JSONObject =
        JSONObject().apply {
            put("name", name)
            id?.let { put("id", it) }
        }

    private fun read(name: String): List<JSONObject>? {
        val file = File(dir, name)
        if (!file.exists()) return null
        return runCatching { JSONArray(file.readText()).objects() }.getOrNull()
    }

    private suspend fun <T> write(name: String, items: List<T>, toJson: (T) -> JSONObject) =
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val array = JSONArray().apply { items.forEach { put(toJson(it)) } }
                File(dir, name).writeText(array.toString())
            }
            Unit
        }
}
