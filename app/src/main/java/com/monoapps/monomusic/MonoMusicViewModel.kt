package com.monoapps.monomusic

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.monoapps.monomusic.offline.OfflineCollection
import com.monoapps.monomusic.tidal.AudioQuality
import com.monoapps.monomusic.tidal.TidalAuthException
import com.monoapps.monomusic.tidal.TidalAlbum
import com.monoapps.monomusic.tidal.TidalArtist
import com.monoapps.monomusic.tidal.TidalPlaylist
import com.monoapps.monomusic.tidal.TidalSearchResults
import com.monoapps.monomusic.tidal.TidalTrack
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object LoggedOut : AuthState
    data class AwaitingApproval(val userCode: String, val url: String) : AuthState
    data object LoggedIn : AuthState
}

data class Loadable<T>(
    val data: T? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MonoMusicViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MonoMusic
    private val session = app.tidalSession
    private val auth = app.tidalAuth
    private val client = app.tidalClient

    // ---- auth ----

    var authState by mutableStateOf<AuthState>(
        if (session.isLoggedIn) AuthState.LoggedIn else AuthState.LoggedOut
    )
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    /** One-shot message surfaced as a snackbar by MainActivity. */
    var uiMessage by mutableStateOf<String?>(null)

    // ---- library ----

    var favoriteTracks by mutableStateOf(Loadable<List<TidalTrack>>())
        private set
    var favoriteAlbums by mutableStateOf(Loadable<List<TidalAlbum>>())
        private set
    var favoriteArtists by mutableStateOf(Loadable<List<TidalArtist>>())
        private set
    var playlists by mutableStateOf(Loadable<List<TidalPlaylist>>())
        private set

    var favoriteTrackIds by mutableStateOf(emptySet<Long>())
        private set
    var favoriteAlbumIds by mutableStateOf(emptySet<Long>())
        private set
    var favoriteArtistIds by mutableStateOf(emptySet<Long>())
        private set

    // ---- details ----

    // Small LRU caches so revisiting a details screen is instant.
    private val albumTracksCache = lruCache<Long, List<TidalTrack>>()
    private val artistTopTracksCache = lruCache<Long, List<TidalTrack>>()
    private val artistAlbumsCache = lruCache<Long, List<TidalAlbum>>()
    private val playlistTracksCache = lruCache<String, List<TidalTrack>>()

    private fun <K, V> lruCache(maxSize: Int = 20): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) =
                size > maxSize
        }

    var selectedAlbum by mutableStateOf<TidalAlbum?>(null)
        private set
    var albumTracks by mutableStateOf(Loadable<List<TidalTrack>>())
        private set
    var selectedArtist by mutableStateOf<TidalArtist?>(null)
        private set
    var artistTopTracks by mutableStateOf(Loadable<List<TidalTrack>>())
        private set
    var artistAlbums by mutableStateOf(Loadable<List<TidalAlbum>>())
        private set
    var selectedPlaylist by mutableStateOf<TidalPlaylist?>(null)
        private set
    var playlistTracks by mutableStateOf(Loadable<List<TidalTrack>>())
        private set

    // ---- search ----

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<TidalSearchResults?>(null)
        private set
    var isSearching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set

    // ---- player ----

    private var controller: MediaController? = null
    private var queueTracks: List<TidalTrack> = emptyList()

    var currentTrack by mutableStateOf<TidalTrack?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var shuffleEnabled by mutableStateOf(false)
        private set
    private var resolvedQualities by mutableStateOf(emptyMap<Long, String>())

    /** Quality Tidal delivered for the current track, once its stream resolved. */
    val streamQuality: String? get() = currentTrack?.id?.let { resolvedQualities[it] }

    val hasNowPlaying: Boolean get() = currentTrack != null

    var audioQuality by mutableStateOf(session.audioQuality)
        private set

    val sessionCountry: String? get() = session.countryCode

    // ---- offline ----

    var offlineCollections by mutableStateOf(emptyList<OfflineCollection>())
        private set
    var offlineTrackIds by mutableStateOf(emptySet<Long>())
        private set
    var offlinePendingCount by mutableStateOf(0)
        private set

    val downloadsOnSdCard: Boolean get() = app.offlineManager.onRemovableStorage
    fun downloadsSizeBytes(): Long = app.offlineManager.sizeBytes()

    fun isPinned(type: String, id: String): Boolean =
        offlineCollections.any { it.key == "$type:$id" }

    fun pinCollection(collection: OfflineCollection) {
        app.offlineManager.pin(collection)
        uiMessage = "Downloading ${collection.title}"
    }

    fun unpinCollection(type: String, id: String) = app.offlineManager.unpin(type, id)

    fun removeAllDownloads() = app.offlineManager.removeAll()

    private var loginJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateCurrentTrack(mediaItem)
            // Gapless auto-advance stays in STATE_READY, so refresh here too;
            // TIME_UNSET maps to 0 and the UI falls back to track metadata.
            durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L
        }

        override fun onRepeatModeChanged(mode: Int) {
            repeatMode = mode
        }

        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            shuffleEnabled = enabled
        }

        override fun onPlayerError(error: PlaybackException) {
            uiMessage = "Playback failed: ${error.cause?.message ?: error.message}"
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val mediaController = future.get()
            controller = mediaController
            mediaController.addListener(playerListener)
            updateCurrentTrack(mediaController.currentMediaItem)
            isPlaying = mediaController.isPlaying
            repeatMode = mediaController.repeatMode
            shuffleEnabled = mediaController.shuffleModeEnabled
            durationMs = mediaController.duration.takeIf { it > 0 } ?: 0L
        }, MoreExecutors.directExecutor())

        viewModelScope.launch {
            app.resolvedQualities.collect { resolvedQualities = it }
        }
        viewModelScope.launch {
            app.offlineManager.collections.collect { offlineCollections = it }
        }
        viewModelScope.launch {
            app.offlineManager.downloadedTrackIds.collect { offlineTrackIds = it }
        }
        viewModelScope.launch {
            app.offlineManager.pendingCount.collect { offlinePendingCount = it }
        }

        if (session.isLoggedIn) {
            viewModelScope.launch {
                runCatching { client.loadSessionInfo() }
            }
            loadLibrary()
        }
    }

    // ---- auth actions ----

    fun startLogin() {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            loginError = null
            try {
                val authorization = auth.requestDeviceAuthorization()
                authState = AuthState.AwaitingApproval(
                    userCode = authorization.userCode,
                    url = "https://${authorization.verificationUriComplete}",
                )
                auth.waitForLogin(authorization)
                client.loadSessionInfo()
                authState = AuthState.LoggedIn
                loadLibrary()
                app.offlineManager.ensureDownloads()
            } catch (e: Exception) {
                authState = AuthState.LoggedOut
                loginError = e.message ?: "Login failed"
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        authState = AuthState.LoggedOut
    }

    fun logout() {
        controller?.stop()
        controller?.clearMediaItems()
        session.clear()
        favoriteTracks = Loadable()
        favoriteAlbums = Loadable()
        favoriteArtists = Loadable()
        playlists = Loadable()
        favoriteTrackIds = emptySet()
        favoriteAlbumIds = emptySet()
        favoriteArtistIds = emptySet()
        searchResults = null
        searchQuery = ""
        currentTrack = null
        queueTracks = emptyList()
        authState = AuthState.LoggedOut
    }

    fun selectAudioQuality(quality: AudioQuality) {
        session.audioQuality = quality
        audioQuality = quality
    }

    // ---- library loading ----

    /** Maps a load failure to a message; a dead refresh token drops back to the login screen. */
    private fun loadError(e: Exception, fallback: String): String {
        if (e is TidalAuthException) logout()
        return e.message ?: fallback
    }

    fun loadLibrary() {
        loadFavoriteTracks()
        loadFavoriteAlbums()
        loadFavoriteArtists()
        loadPlaylists()
    }

    fun loadFavoriteTracks() {
        viewModelScope.launch {
            favoriteTracks = favoriteTracks.copy(isLoading = true, error = null)
            favoriteTracks = try {
                val tracks = client.favoriteTracks()
                favoriteTrackIds = tracks.map { it.id }.toSet()
                Loadable(tracks)
            } catch (e: Exception) {
                Loadable(favoriteTracks.data, error = loadError(e, "Could not load songs"))
            }
        }
    }

    fun loadFavoriteAlbums() {
        viewModelScope.launch {
            favoriteAlbums = favoriteAlbums.copy(isLoading = true, error = null)
            favoriteAlbums = try {
                val albums = client.favoriteAlbums()
                favoriteAlbumIds = albums.map { it.id }.toSet()
                Loadable(albums)
            } catch (e: Exception) {
                Loadable(favoriteAlbums.data, error = loadError(e, "Could not load albums"))
            }
        }
    }

    fun loadFavoriteArtists() {
        viewModelScope.launch {
            favoriteArtists = favoriteArtists.copy(isLoading = true, error = null)
            favoriteArtists = try {
                val artists = client.favoriteArtists()
                favoriteArtistIds = artists.map { it.id }.toSet()
                Loadable(artists)
            } catch (e: Exception) {
                Loadable(favoriteArtists.data, error = loadError(e, "Could not load artists"))
            }
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            playlists = playlists.copy(isLoading = true, error = null)
            playlists = try {
                Loadable(client.userPlaylists())
            } catch (e: Exception) {
                Loadable(playlists.data, error = loadError(e, "Could not load playlists"))
            }
        }
    }

    // ---- search ----

    fun performSearch() {
        val query = searchQuery.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            isSearching = true
            searchError = null
            try {
                searchResults = client.search(query)
            } catch (e: Exception) {
                searchError = e.message ?: "Search failed"
            }
            isSearching = false
        }
    }

    // ---- details ----

    fun openAlbum(album: TidalAlbum) {
        selectedAlbum = album
        albumTracksCache[album.id]?.let {
            albumTracks = Loadable(it)
            return
        }
        albumTracks = Loadable(isLoading = true)
        viewModelScope.launch {
            albumTracks = try {
                val tracks = client.albumTracks(album.id)
                albumTracksCache[album.id] = tracks
                Loadable(tracks)
            } catch (e: Exception) {
                Loadable(error = e.message ?: "Could not load album")
            }
        }
    }

    fun openArtist(artist: TidalArtist) {
        selectedArtist = artist
        val cachedTop = artistTopTracksCache[artist.id]
        val cachedAlbums = artistAlbumsCache[artist.id]
        artistTopTracks = cachedTop?.let { Loadable(it) } ?: Loadable(isLoading = true)
        artistAlbums = cachedAlbums?.let { Loadable(it) } ?: Loadable(isLoading = true)
        if (cachedTop == null) {
            viewModelScope.launch {
                artistTopTracks = try {
                    val tracks = client.artistTopTracks(artist.id)
                    artistTopTracksCache[artist.id] = tracks
                    Loadable(tracks)
                } catch (e: Exception) {
                    Loadable(error = e.message ?: "Could not load top songs")
                }
            }
        }
        if (cachedAlbums == null) {
            viewModelScope.launch {
                artistAlbums = try {
                    val albums = client.artistAlbums(artist.id)
                    val extras = runCatching { client.artistEpsAndSingles(artist.id) }
                        .getOrDefault(emptyList())
                    val seen = albums.map { it.id }.toSet()
                    val merged = albums + extras.filter { it.id !in seen }
                    artistAlbumsCache[artist.id] = merged
                    Loadable(merged)
                } catch (e: Exception) {
                    Loadable(error = loadError(e, "Could not load albums"))
                }
            }
        }
    }

    fun openPlaylist(playlist: TidalPlaylist) {
        selectedPlaylist = playlist
        playlistTracksCache[playlist.uuid]?.let {
            playlistTracks = Loadable(it)
            return
        }
        playlistTracks = Loadable(isLoading = true)
        viewModelScope.launch {
            playlistTracks = try {
                val tracks = client.playlistTracks(playlist.uuid)
                playlistTracksCache[playlist.uuid] = tracks
                Loadable(tracks)
            } catch (e: Exception) {
                Loadable(error = e.message ?: "Could not load playlist")
            }
        }
    }

    // ---- favorites ----

    fun toggleFavoriteTrack(track: TidalTrack) {
        val isFavorite = track.id in favoriteTrackIds
        applyTrackFavorite(track, nowFavorite = !isFavorite)
        viewModelScope.launch {
            try {
                if (isFavorite) client.removeFavoriteTrack(track.id)
                else client.addFavoriteTrack(track.id)
            } catch (e: Exception) {
                applyTrackFavorite(track, nowFavorite = isFavorite)
                uiMessage = e.message ?: "Could not update favorites"
            }
        }
    }

    // Edit the local list in place; a full refetch pages through the whole
    // favorites list and makes every toggle feel slow.
    private fun applyTrackFavorite(track: TidalTrack, nowFavorite: Boolean) {
        favoriteTrackIds =
            if (nowFavorite) favoriteTrackIds + track.id else favoriteTrackIds - track.id
        favoriteTracks.data?.let { list ->
            favoriteTracks = favoriteTracks.copy(
                data = if (nowFavorite) {
                    (list.filterNot { it.id == track.id } + track)
                        .sortedBy { it.title.lowercase() }
                } else {
                    list.filterNot { it.id == track.id }
                }
            )
        }
    }

    fun toggleFavoriteAlbum(album: TidalAlbum) {
        val isFavorite = album.id in favoriteAlbumIds
        applyAlbumFavorite(album, nowFavorite = !isFavorite)
        viewModelScope.launch {
            try {
                if (isFavorite) client.removeFavoriteAlbum(album.id)
                else client.addFavoriteAlbum(album.id)
            } catch (e: Exception) {
                applyAlbumFavorite(album, nowFavorite = isFavorite)
                uiMessage = e.message ?: "Could not update favorites"
            }
        }
    }

    private fun applyAlbumFavorite(album: TidalAlbum, nowFavorite: Boolean) {
        favoriteAlbumIds =
            if (nowFavorite) favoriteAlbumIds + album.id else favoriteAlbumIds - album.id
        favoriteAlbums.data?.let { list ->
            favoriteAlbums = favoriteAlbums.copy(
                data = if (nowFavorite) {
                    (list.filterNot { it.id == album.id } + album)
                        .sortedBy { it.title.lowercase() }
                } else {
                    list.filterNot { it.id == album.id }
                }
            )
        }
    }

    fun toggleFavoriteArtist(artist: TidalArtist) {
        val isFavorite = artist.id in favoriteArtistIds
        applyArtistFavorite(artist, nowFavorite = !isFavorite)
        viewModelScope.launch {
            try {
                if (isFavorite) client.removeFavoriteArtist(artist.id)
                else client.addFavoriteArtist(artist.id)
            } catch (e: Exception) {
                applyArtistFavorite(artist, nowFavorite = isFavorite)
                uiMessage = e.message ?: "Could not update favorites"
            }
        }
    }

    private fun applyArtistFavorite(artist: TidalArtist, nowFavorite: Boolean) {
        favoriteArtistIds =
            if (nowFavorite) favoriteArtistIds + artist.id else favoriteArtistIds - artist.id
        favoriteArtists.data?.let { list ->
            favoriteArtists = favoriteArtists.copy(
                data = if (nowFavorite) {
                    (list.filterNot { it.id == artist.id } + artist)
                        .sortedBy { it.name.lowercase() }
                } else {
                    list.filterNot { it.id == artist.id }
                }
            )
        }
    }

    // ---- playlists ----

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            try {
                client.createPlaylist(name)
                uiMessage = "Playlist created"
                loadPlaylists()
            } catch (e: Exception) {
                uiMessage = e.message ?: "Could not create playlist"
            }
        }
    }

    fun addToPlaylist(track: TidalTrack, playlist: TidalPlaylist) {
        viewModelScope.launch {
            try {
                client.addTrackToPlaylist(playlist.uuid, track.id)
                uiMessage = "Added to ${playlist.title}"
                playlistTracksCache.remove(playlist.uuid)
                loadPlaylists()
                if (selectedPlaylist?.uuid == playlist.uuid) openPlaylist(playlist)
            } catch (e: Exception) {
                uiMessage = e.message ?: "Could not add to playlist"
            }
        }
    }

    fun removeFromPlaylist(index: Int) {
        val playlist = selectedPlaylist ?: return
        viewModelScope.launch {
            try {
                client.removePlaylistItem(playlist.uuid, index)
                playlistTracksCache.remove(playlist.uuid)
                openPlaylist(playlist)
                loadPlaylists()
            } catch (e: Exception) {
                uiMessage = e.message ?: "Could not remove song"
            }
        }
    }

    fun deletePlaylist(playlist: TidalPlaylist) {
        viewModelScope.launch {
            try {
                client.removePlaylistFromCollection(playlist.uuid)
                uiMessage = "Playlist removed"
                playlistTracksCache.remove(playlist.uuid)
                loadPlaylists()
            } catch (e: Exception) {
                uiMessage = e.message ?: "Could not remove playlist"
            }
        }
    }

    // ---- playback ----

    fun playTracks(tracks: List<TidalTrack>, startIndex: Int = 0, shuffled: Boolean = false) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        queueTracks = tracks
        val items = tracks.map { it.toMediaItem() }
        val start = if (shuffled) tracks.indices.random() else startIndex
        player.shuffleModeEnabled = shuffled
        player.setMediaItems(items, start, 0L)
        player.prepare()
        player.play()
    }

    fun playPause() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() = controller?.seekToNextMediaItem() ?: Unit

    fun previous() {
        val player = controller ?: return
        // Mirror normal player behaviour: restart late into a song, else go back.
        if (player.currentPosition > 3000) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs) ?: Unit

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L

    fun toggleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    private fun updateCurrentTrack(mediaItem: MediaItem?) {
        val id = mediaItem?.mediaId?.toLongOrNull()
        currentTrack = queueTracks.firstOrNull { it.id == id }
            ?: id?.let { trackFromMetadata(it, mediaItem.mediaMetadata) }
    }

    private fun trackFromMetadata(id: Long, metadata: MediaMetadata): TidalTrack =
        TidalTrack(
            id = id,
            title = metadata.title?.toString() ?: "Unknown",
            artistName = metadata.artist?.toString() ?: "",
            artistId = null,
            albumTitle = metadata.albumTitle?.toString(),
            albumId = null,
            durationSec = 0,
            trackNumber = 0,
            volumeNumber = 1,
            explicit = false,
            audioQuality = null,
        )

    override fun onCleared() {
        controller?.release()
        controller = null
        super.onCleared()
    }
}

fun TidalTrack.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("tidal://track/$id")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistName)
                .setAlbumTitle(albumTitle)
                .build()
        )
        .build()
