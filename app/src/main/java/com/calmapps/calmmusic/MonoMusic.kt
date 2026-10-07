package com.calmapps.calmmusic

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.calmapps.calmmusic.tidal.TidalAuth
import com.calmapps.calmmusic.tidal.TidalClient
import com.calmapps.calmmusic.tidal.TidalSession
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.io.File

@UnstableApi
class MonoMusic : Application() {

    val httpClient: OkHttpClient by lazy { OkHttpClient() }
    val tidalSession: TidalSession by lazy { TidalSession(this) }
    val tidalAuth: TidalAuth by lazy { TidalAuth(tidalSession, httpClient) }
    val tidalClient: TidalClient by lazy { TidalClient(tidalSession, tidalAuth, httpClient) }

    /** Quality Tidal actually delivered for the current stream, e.g. "LOSSLESS". */
    val nowPlayingQuality = MutableStateFlow<String?>(null)

    val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(this),
        )
    }
}
