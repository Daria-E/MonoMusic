package com.monoapps.monomusic.tidal

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** Parsing of Tidal API JSON into models; separate from the client so it is unit-testable. */
internal object TidalJson {

    /**
     * Null-safe string read. Android's org.json renders explicit JSON nulls
     * as the literal string "null" from optString, so always check isNull.
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    fun parseTrack(o: JSONObject): TidalTrack? {
        val id = o.optLong("id", -1)
        if (id <= 0) return null
        val artist = o.optJSONObject("artist")
        val album = o.optJSONObject("album")
        return TidalTrack(
            id = id,
            title = o.stringOrNull("title") ?: "",
            artistName = artist?.stringOrNull("name") ?: "",
            artistId = artist?.optLong("id")?.takeIf { it > 0 },
            albumTitle = album?.stringOrNull("title"),
            albumId = album?.optLong("id")?.takeIf { it > 0 },
            durationSec = o.optInt("duration"),
            trackNumber = o.optInt("trackNumber"),
            volumeNumber = o.optInt("volumeNumber", 1),
            explicit = o.optBoolean("explicit"),
            audioQuality = o.stringOrNull("audioQuality"),
            // "addedAt" is our own cache format; "created" the favorites API's.
            addedAt = if (o.has("addedAt")) o.optLong("addedAt") else createdMillis(o),
        )
    }

    private fun createdMillis(o: JSONObject): Long {
        val created = o.stringOrNull("created") ?: return 0L
        // New instance per call: SimpleDateFormat is not thread-safe.
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).parse(created)?.time
        }.getOrNull() ?: 0L
    }

    fun parseAlbum(o: JSONObject): TidalAlbum? {
        val id = o.optLong("id", -1)
        if (id <= 0) return null
        val artist = o.optJSONObject("artist")
        return TidalAlbum(
            id = id,
            title = o.stringOrNull("title") ?: "",
            artistName = artist?.stringOrNull("name") ?: "",
            artistId = artist?.optLong("id")?.takeIf { it > 0 },
            numberOfTracks = o.optInt("numberOfTracks"),
            releaseYear = o.stringOrNull("releaseDate")
                ?.takeIf { it.length >= 4 }?.substring(0, 4),
            audioQuality = o.stringOrNull("audioQuality"),
        )
    }

    fun parseArtist(o: JSONObject): TidalArtist? {
        val id = o.optLong("id", -1)
        if (id <= 0) return null
        return TidalArtist(id = id, name = o.stringOrNull("name") ?: "")
    }

    fun parsePlaylist(o: JSONObject): TidalPlaylist? {
        val uuid = o.stringOrNull("uuid") ?: return null
        val creator = when {
            o.stringOrNull("type") == "EDITORIAL" -> "TIDAL"
            else -> o.optJSONObject("creator")?.stringOrNull("name")
        }
        return TidalPlaylist(
            uuid = uuid,
            title = o.stringOrNull("title") ?: "",
            numberOfTracks = o.optInt("numberOfTracks"),
            description = o.stringOrNull("description"),
            creator = creator,
        )
    }

    /** Decodes the JSON inside a BTS (vnd.tidal.bts) manifest into a stream info. */
    fun parseBtsManifest(manifestJson: String, deliveredQuality: String): TidalStreamInfo {
        val manifest = JSONObject(manifestJson)
        if ((manifest.stringOrNull("encryptionType") ?: "NONE") != "NONE") {
            throw TidalApiException(0, "Encrypted streams are not supported")
        }
        val urls = manifest.optJSONArray("urls")
        if (urls == null || urls.length() == 0) {
            throw TidalApiException(0, "Tidal returned no stream URL")
        }
        return TidalStreamInfo(
            url = urls.getString(0),
            codec = manifest.stringOrNull("codecs"),
            audioQuality = deliveredQuality,
        )
    }
}
