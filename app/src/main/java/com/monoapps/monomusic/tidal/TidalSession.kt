package com.monoapps.monomusic.tidal

import android.content.Context

/**
 * Persisted Tidal session: OAuth tokens plus the session metadata
 * (country, user id) that every API call needs.
 */
class TidalSession(context: Context) {
    private val prefs = context.getSharedPreferences("tidal_session", Context.MODE_PRIVATE)

    val accessToken: String? get() = prefs.getString("access_token", null)
    val refreshToken: String? get() = prefs.getString("refresh_token", null)
    val tokenType: String get() = prefs.getString("token_type", "Bearer") ?: "Bearer"
    val expiresAtMillis: Long get() = prefs.getLong("expires_at", 0L)
    val countryCode: String? get() = prefs.getString("country_code", null)
    val userId: Long get() = prefs.getLong("user_id", 0L)
    val sessionId: String? get() = prefs.getString("session_id", null)

    val isLoggedIn: Boolean get() = !refreshToken.isNullOrEmpty()

    var audioQuality: AudioQuality
        get() = prefs.getString("audio_quality", null)
            ?.let { stored -> AudioQuality.entries.firstOrNull { it.name == stored } }
            ?: AudioQuality.LOSSLESS
        set(value) = prefs.edit().putString("audio_quality", value.name).apply()

    var downloadQuality: AudioQuality
        get() = prefs.getString("download_quality", null)
            ?.let { stored -> AudioQuality.entries.firstOrNull { it.name == stored } }
            ?: AudioQuality.LOSSLESS
        set(value) = prefs.edit().putString("download_quality", value.name).apply()

    // Tokens live in plain SharedPreferences: app-private storage on a
    // single-user device, same trade-off python-tidal's session file makes.
    fun saveTokens(accessToken: String, refreshToken: String?, tokenType: String, expiresInSec: Long) {
        val editor = prefs.edit()
            .putString("access_token", accessToken)
            .putString("token_type", tokenType)
            .putLong("expires_at", System.currentTimeMillis() + expiresInSec * 1000)
        // Refresh responses carry no new refresh token; keep the old one.
        if (refreshToken != null) editor.putString("refresh_token", refreshToken)
        editor.apply()
    }

    fun saveSessionInfo(sessionId: String, countryCode: String, userId: Long) {
        prefs.edit()
            .putString("session_id", sessionId)
            .putString("country_code", countryCode)
            .putLong("user_id", userId)
            .apply()
    }

    fun clear() {
        val streaming = audioQuality
        val download = downloadQuality
        prefs.edit().clear().apply()
        audioQuality = streaming
        downloadQuality = download
    }
}
