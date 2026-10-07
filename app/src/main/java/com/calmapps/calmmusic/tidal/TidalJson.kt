package com.calmapps.calmmusic.tidal

import org.json.JSONArray
import org.json.JSONObject

/** Parsing of Tidal API JSON into models; separate from the client so it is unit-testable. */
internal object TidalJson {

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
            title = o.optString("title"),
            artistName = artist?.optString("name") ?: "",
            artistId = artist?.optLong("id")?.takeIf { it > 0 },
            albumTitle = album?.optString("title")?.takeIf { it.isNotEmpty() },
            albumId = album?.optLong("id")?.takeIf { it > 0 },
            durationSec = o.optInt("duration"),
            trackNumber = o.optInt("trackNumber"),
            volumeNumber = o.optInt("volumeNumber", 1),
            explicit = o.optBoolean("explicit"),
            audioQuality = o.optString("audioQuality").takeIf { it.isNotEmpty() },
        )
    }

    fun parseAlbum(o: JSONObject): TidalAlbum? {
        val id = o.optLong("id", -1)
        if (id <= 0) return null
        val artist = o.optJSONObject("artist")
        return TidalAlbum(
            id = id,
            title = o.optString("title"),
            artistName = artist?.optString("name") ?: "",
            artistId = artist?.optLong("id")?.takeIf { it > 0 },
            numberOfTracks = o.optInt("numberOfTracks"),
            releaseYear = o.optString("releaseDate").takeIf { it.length >= 4 }?.substring(0, 4),
            audioQuality = o.optString("audioQuality").takeIf { it.isNotEmpty() },
        )
    }

    fun parseArtist(o: JSONObject): TidalArtist? {
        val id = o.optLong("id", -1)
        if (id <= 0) return null
        return TidalArtist(id = id, name = o.optString("name"))
    }

    fun parsePlaylist(o: JSONObject): TidalPlaylist? {
        val uuid = o.optString("uuid")
        if (uuid.isEmpty()) return null
        val creator = when {
            o.optString("type") == "EDITORIAL" -> "TIDAL"
            else -> o.optJSONObject("creator")?.optString("name")?.takeIf { it.isNotEmpty() }
        }
        return TidalPlaylist(
            uuid = uuid,
            title = o.optString("title"),
            numberOfTracks = o.optInt("numberOfTracks"),
            description = o.optString("description").takeIf { it.isNotEmpty() },
            creator = creator,
        )
    }

    /** Decodes the JSON inside a BTS (vnd.tidal.bts) manifest into a stream info. */
    fun parseBtsManifest(manifestJson: String, deliveredQuality: String): TidalStreamInfo {
        val manifest = JSONObject(manifestJson)
        if (manifest.optString("encryptionType", "NONE") != "NONE") {
            throw TidalApiException(0, "Encrypted streams are not supported")
        }
        val urls = manifest.optJSONArray("urls")
        if (urls == null || urls.length() == 0) {
            throw TidalApiException(0, "Tidal returned no stream URL")
        }
        return TidalStreamInfo(
            url = urls.getString(0),
            codec = manifest.optString("codecs").takeIf { it.isNotEmpty() },
            audioQuality = deliveredQuality,
        )
    }
}
