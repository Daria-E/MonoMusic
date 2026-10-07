package com.monoapps.monomusic.tidal

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.monoapps.monomusic.tidal.TidalJson.objects
import com.monoapps.monomusic.tidal.TidalJson.parseAlbum
import com.monoapps.monomusic.tidal.TidalJson.parseArtist
import com.monoapps.monomusic.tidal.TidalJson.parsePlaylist
import com.monoapps.monomusic.tidal.TidalJson.parseTrack

class TidalApiException(val httpCode: Int, message: String) : Exception(message)

/**
 * Minimal client for the unofficial Tidal API (api.tidal.com/v1 and /v2),
 * mirroring what python-tidal does. Requires a logged-in [TidalSession].
 */
class TidalClient(
    private val session: TidalSession,
    private val auth: TidalAuth,
    private val http: OkHttpClient,
) {
    companion object {
        private const val API_V1 = "https://api.tidal.com/v1/"
        private const val API_V2 = "https://api.tidal.com/v2/"
        private const val CLIENT_VERSION = "2025.7.16"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/91.0.4472.114 Safari/537.36"
    }

    // ---- session ----

    /** Fetches session id / country / user id for the current token and persists them. */
    suspend fun loadSessionInfo() {
        val json = request { get(url(API_V1 + "sessions")) }.asJson()
        session.saveSessionInfo(
            sessionId = json.getString("sessionId"),
            countryCode = json.getString("countryCode"),
            userId = json.getLong("userId"),
        )
    }

    // ---- search & browse ----

    suspend fun search(query: String): TidalSearchResults {
        val json = request {
            get(
                url(
                    API_V1 + "search",
                    "query" to query,
                    "types" to "tracks,albums,artists,playlists",
                    "limit" to "25",
                )
            )
        }.asJson()
        fun items(key: String) = json.optJSONObject(key)?.optJSONArray("items") ?: JSONArray()
        return TidalSearchResults(
            tracks = items("tracks").objects().mapNotNull(::parseTrack),
            albums = items("albums").objects().mapNotNull(::parseAlbum),
            artists = items("artists").objects().mapNotNull(::parseArtist),
            playlists = items("playlists").objects().mapNotNull(::parsePlaylist),
        )
    }

    suspend fun albumTracks(albumId: Long): List<TidalTrack> =
        paged(API_V1 + "albums/$albumId/tracks", pageSize = 100).mapNotNull(::parseTrack)

    suspend fun artistAlbums(artistId: Long): List<TidalAlbum> =
        paged(API_V1 + "artists/$artistId/albums", pageSize = 50).mapNotNull(::parseAlbum)

    suspend fun artistEpsAndSingles(artistId: Long): List<TidalAlbum> =
        paged(
            API_V1 + "artists/$artistId/albums",
            pageSize = 50,
            extraParams = arrayOf("filter" to "EPSANDSINGLES"),
        ).mapNotNull(::parseAlbum)

    suspend fun artistTopTracks(artistId: Long, limit: Int = 20): List<TidalTrack> {
        val json = request {
            get(url(API_V1 + "artists/$artistId/toptracks", "limit" to limit.toString()))
        }.asJson()
        return json.optJSONArray("items").objects().mapNotNull(::parseTrack)
    }

    // ---- favorites ----

    suspend fun favoriteTracks(): List<TidalTrack> =
        paged(
            API_V1 + "users/${session.userId}/favorites/tracks",
            pageSize = 100,
            unwrapKey = "item",
            extraParams = arrayOf("order" to "NAME", "orderDirection" to "ASC"),
        ).mapNotNull(::parseTrack)

    suspend fun favoriteAlbums(): List<TidalAlbum> =
        paged(
            API_V1 + "users/${session.userId}/favorites/albums",
            pageSize = 100,
            unwrapKey = "item",
            extraParams = arrayOf("order" to "NAME", "orderDirection" to "ASC"),
        ).mapNotNull(::parseAlbum)

    suspend fun favoriteArtists(): List<TidalArtist> =
        paged(
            API_V1 + "users/${session.userId}/favorites/artists",
            pageSize = 100,
            unwrapKey = "item",
            extraParams = arrayOf("order" to "NAME", "orderDirection" to "ASC"),
        ).mapNotNull(::parseArtist)

    suspend fun addFavoriteTrack(trackId: Long) =
        favoritesMutation("tracks", "trackId" to trackId.toString())

    suspend fun removeFavoriteTrack(trackId: Long) =
        request { delete(url(API_V1 + "users/${session.userId}/favorites/tracks/$trackId")) }.close()

    suspend fun addFavoriteAlbum(albumId: Long) =
        favoritesMutation("albums", "albumId" to albumId.toString())

    suspend fun removeFavoriteAlbum(albumId: Long) =
        request { delete(url(API_V1 + "users/${session.userId}/favorites/albums/$albumId")) }.close()

    suspend fun addFavoriteArtist(artistId: Long) =
        favoritesMutation("artists", "artistId" to artistId.toString())

    suspend fun removeFavoriteArtist(artistId: Long) =
        request { delete(url(API_V1 + "users/${session.userId}/favorites/artists/$artistId")) }.close()

    private suspend fun favoritesMutation(kind: String, field: Pair<String, String>) {
        val body = FormBody.Builder().add(field.first, field.second).build()
        request { post(url(API_V1 + "users/${session.userId}/favorites/$kind"), body) }.close()
    }

    // ---- playlists ----

    suspend fun userPlaylists(): List<TidalPlaylist> =
        paged(
            API_V1 + "users/${session.userId}/playlistsAndFavoritePlaylists",
            pageSize = 50,
            unwrapKey = "playlist",
        ).mapNotNull(::parsePlaylist)

    suspend fun playlistTracks(uuid: String): List<TidalTrack> =
        paged(API_V1 + "playlists/$uuid/tracks", pageSize = 100).mapNotNull(::parseTrack)

    suspend fun createPlaylist(name: String, description: String = ""): TidalPlaylist? {
        val json = request {
            put(
                url(
                    API_V2 + "my-collection/playlists/folders/create-playlist",
                    "name" to name,
                    "description" to description,
                    "folderId" to "root",
                ),
                FormBody.Builder().build(),
            )
        }.asJson()
        return json.optJSONObject("data")?.let(::parsePlaylist)
    }

    suspend fun addTrackToPlaylist(uuid: String, trackId: Long) {
        val etag = playlistEtag(uuid)
        val body = FormBody.Builder()
            .add("trackIds", trackId.toString())
            .add("onArtifactNotFound", "SKIP")
            .add("onDupes", "SKIP")
            .build()
        request {
            post(url(API_V1 + "playlists/$uuid/items"), body)
                .apply { if (etag != null) header("If-None-Match", etag) }
        }.close()
    }

    /** Removes a playlist entry by its position (the Tidal API deletes by index, not track id). */
    suspend fun removePlaylistItem(uuid: String, index: Int) {
        val etag = playlistEtag(uuid)
        request {
            delete(url(API_V1 + "playlists/$uuid/items/$index"))
                .apply { if (etag != null) header("If-None-Match", etag) }
        }.close()
    }

    /**
     * Removes a playlist from the user's collection: own playlists are
     * deleted outright, followed ones unfavorited via the v2 endpoint.
     */
    suspend fun removePlaylistFromCollection(uuid: String) {
        try {
            request { delete(url(API_V1 + "playlists/$uuid")) }.close()
        } catch (e: TidalApiException) {
            // Only "not your playlist" means unfavorite instead; anything else is a real error.
            if (e.httpCode !in setOf(401, 403, 404)) throw e
            request {
                put(
                    url(
                        API_V2 + "my-collection/playlists/folders/remove",
                        "trns" to "trn:playlist:$uuid",
                    ),
                    FormBody.Builder().build(),
                )
            }.close()
        }
    }

    private suspend fun playlistEtag(uuid: String): String? =
        request { get(url(API_V1 + "playlists/$uuid")) }.use { it.header("ETag") }

    // ---- streaming ----

    /**
     * Resolves a track to a direct stream URL via playbackinfopostpaywall.
     * The device-flow client returns a BTS manifest (a JSON wrapper around a
     * signed direct URL) for every quality it can deliver.
     *
     * Tidal rejects qualities the subscription (or this client tier) does
     * not include, so step down from the selected quality until one works;
     * the delivered quality is reported in the result either way.
     */
    suspend fun streamInfo(trackId: Long): TidalStreamInfo {
        val fallbackChain = AudioQuality.entries
            .filter { it.ordinal <= session.audioQuality.ordinal }
            .sortedByDescending { it.ordinal }
        var lastError: TidalApiException? = null
        for (quality in fallbackChain) {
            try {
                return streamInfoAt(trackId, quality)
            } catch (e: TidalApiException) {
                // 4xx (except rate limiting) means "not allowed at this
                // quality"; anything else is a real failure.
                if (e.httpCode !in 400..403) throw e
                lastError = e
            }
        }
        throw lastError ?: TidalApiException(0, "Could not resolve stream")
    }

    private suspend fun streamInfoAt(trackId: Long, quality: AudioQuality): TidalStreamInfo {
        val json = request {
            get(
                url(
                    API_V1 + "tracks/$trackId/playbackinfopostpaywall",
                    "playbackmode" to "STREAM",
                    "assetpresentation" to "FULL",
                    "audioquality" to quality.name,
                )
            )
        }.asJson()
        val mimeType = json.optString("manifestMimeType")
        if (mimeType != "application/vnd.tidal.bts") {
            throw TidalApiException(0, "Unsupported stream manifest: $mimeType")
        }
        return TidalJson.parseBtsManifest(
            manifestJson = String(Base64.decode(json.getString("manifest"), Base64.DEFAULT)),
            deliveredQuality = json.optString("audioQuality", quality.name),
        )
    }

    // ---- request plumbing ----

    private fun url(base: String, vararg params: Pair<String, String>): HttpUrl {
        val builder = base.toHttpUrl().newBuilder()
        session.countryCode?.let { builder.addQueryParameter("countryCode", it) }
        session.sessionId?.let { builder.addQueryParameter("sessionId", it) }
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private fun get(url: HttpUrl) = Request.Builder().url(url).get()
    private fun post(url: HttpUrl, body: RequestBody) = Request.Builder().url(url).post(body)
    private fun put(url: HttpUrl, body: RequestBody) = Request.Builder().url(url).put(body)
    private fun delete(url: HttpUrl) = Request.Builder().url(url).delete()

    /**
     * Executes a request with auth headers; refreshes the access token
     * proactively near expiry and reactively on 401, and honours one
     * Retry-After on 429. The builder is re-invoked per attempt so retries
     * pick up a refreshed token.
     */
    private suspend fun request(build: () -> Request.Builder): okhttp3.Response {
        if (System.currentTimeMillis() >= session.expiresAtMillis - 60_000) {
            auth.refreshAccessToken()
        }
        var response = execute(build)
        if (response.code == 401) {
            response.close()
            auth.refreshAccessToken(force = true)
            response = execute(build)
        }
        if (response.code == 429) {
            val waitSec = response.header("Retry-After")?.toLongOrNull() ?: 2L
            response.close()
            delay(waitSec.coerceAtMost(15) * 1000)
            response = execute(build)
        }
        if (!response.isSuccessful) {
            val code = response.code
            val message = response.use { resp ->
                runCatching { JSONObject(resp.body?.string() ?: "") }
                    .getOrNull()?.optString("userMessage")?.takeIf { it.isNotEmpty() }
            } ?: "Tidal request failed (HTTP $code)"
            throw TidalApiException(code, message)
        }
        return response
    }

    private suspend fun execute(build: () -> Request.Builder): okhttp3.Response =
        withContext(Dispatchers.IO) {
            val request = build()
                .header("Authorization", "${session.tokenType} ${session.accessToken}")
                .header("User-Agent", USER_AGENT)
                .header("x-tidal-client-version", CLIENT_VERSION)
                .build()
            http.newCall(request).execute()
        }

    private fun okhttp3.Response.asJson(): JSONObject =
        use { JSONObject(it.body?.string() ?: "{}") }

    /**
     * Fetches all pages of a `{limit, offset, totalNumberOfItems, items}` list
     * endpoint. Capped at [maxItems]; a favorites list beyond that loses its tail.
     */
    private suspend fun paged(
        base: String,
        pageSize: Int,
        unwrapKey: String? = null,
        extraParams: Array<Pair<String, String>> = emptyArray(),
        maxItems: Int = 1000,
    ): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var offset = 0
        while (offset < maxItems) {
            val json = request {
                get(
                    url(
                        base,
                        "limit" to pageSize.toString(),
                        "offset" to offset.toString(),
                        *extraParams,
                    )
                )
            }.asJson()
            val items = json.optJSONArray("items").objects()
            items.forEach { entry ->
                result.add(if (unwrapKey != null) entry.optJSONObject(unwrapKey) ?: entry else entry)
            }
            val total = json.optInt("totalNumberOfItems", result.size)
            offset += pageSize
            if (items.size < pageSize || result.size >= total) break
        }
        return result
    }

}
