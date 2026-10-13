package com.monoapps.monomusic.tidal

data class TidalTrack(
    val id: Long,
    val title: String,
    val artistName: String,
    val artistId: Long?,
    val albumTitle: String?,
    val albumId: Long?,
    val durationSec: Int,
    val trackNumber: Int,
    val volumeNumber: Int,
    val explicit: Boolean,
    val audioQuality: String?,
    /** Millis the song entered the library (favorited/downloaded/liked); 0 when unknown. */
    val addedAt: Long = 0,
)

data class TidalAlbum(
    val id: Long,
    val title: String,
    val artistName: String,
    val artistId: Long?,
    val numberOfTracks: Int,
    val releaseYear: String?,
    val audioQuality: String?,
)

data class TidalArtist(
    val id: Long,
    val name: String,
)

data class TidalPlaylist(
    val uuid: String,
    val title: String,
    val numberOfTracks: Int,
    val description: String?,
    val creator: String?,
)

data class TidalSearchResults(
    val tracks: List<TidalTrack>,
    val albums: List<TidalAlbum>,
    val artists: List<TidalArtist>,
    val playlists: List<TidalPlaylist>,
)

/** A resolved, directly playable (but expiring) stream URL. */
data class TidalStreamInfo(
    val url: String,
    val codec: String?,
    /** Quality actually delivered by Tidal, e.g. LOSSLESS when HIGH was asked but unavailable. */
    val audioQuality: String,
)

data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUriComplete: String,
    val expiresInSec: Int,
    val pollIntervalSec: Int,
)

/** Qualities reachable with the device-flow client (HI_RES_LOSSLESS needs the PKCE client). */
enum class AudioQuality(val label: String) {
    LOW("Low (96 kbps AAC)"),
    HIGH("High (320 kbps AAC)"),
    LOSSLESS("Lossless (FLAC)"),
}
