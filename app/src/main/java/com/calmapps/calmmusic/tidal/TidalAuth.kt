package com.calmapps.calmmusic.tidal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class TidalAuthException(message: String) : Exception(message)

/**
 * OAuth2 device-authorization flow against auth.tidal.com, same flow and
 * public client credentials as the python-tidal project. This client tier
 * unlocks up to LOSSLESS (16-bit/44.1 kHz FLAC).
 */
class TidalAuth(private val session: TidalSession, private val http: OkHttpClient) {

    companion object {
        private const val AUTH_BASE = "https://auth.tidal.com/v1/oauth2"
        private const val SCOPE = "r_usr w_usr w_sub"

        // Public device-flow client shipped by python-tidal. Tidal rotates
        // these occasionally; when login starts failing, check tidalapi for
        // the current pair.
        const val CLIENT_ID = "fX2JxdmntZWK0ixT"
        const val CLIENT_SECRET = "1Nn9AfDAjxrgJFJbKNWLeAyKGVGmINuXPPLHVXAvxAg="
    }

    private val refreshMutex = Mutex()

    suspend fun requestDeviceAuthorization(): DeviceAuthorization = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val request = Request.Builder().url("$AUTH_BASE/device_authorization").post(body).build()
        http.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            if (!response.isSuccessful) {
                throw TidalAuthException("Could not start Tidal login (HTTP ${response.code})")
            }
            DeviceAuthorization(
                deviceCode = json.getString("deviceCode"),
                userCode = json.getString("userCode"),
                verificationUriComplete = json.getString("verificationUriComplete"),
                expiresInSec = json.getInt("expiresIn"),
                pollIntervalSec = json.optInt("interval", 2),
            )
        }
    }

    /**
     * Polls the token endpoint until the user approves the login in their
     * browser, then persists the tokens. Throws when the code expires.
     */
    suspend fun waitForLogin(authorization: DeviceAuthorization) {
        val deadline = System.currentTimeMillis() + authorization.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(authorization.pollIntervalSec * 1000L)
            val json = postToken(
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
                "device_code" to authorization.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "scope" to SCOPE,
            )
            when {
                json.has("access_token") -> {
                    session.saveTokens(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
                        tokenType = json.optString("token_type", "Bearer"),
                        expiresInSec = json.optLong("expires_in", 3600),
                    )
                    return
                }
                json.optString("error") == "expired_token" ->
                    throw TidalAuthException("The login code expired, try again")
                // authorization_pending and transient errors: keep polling
            }
        }
        throw TidalAuthException("The login code expired, try again")
    }

    /**
     * Refreshes the access token. With [force] false this is a no-op while
     * the current token is still comfortably valid, so concurrent callers
     * (UI and playback resolver) collapse into one refresh.
     */
    suspend fun refreshAccessToken(force: Boolean = false) {
        refreshMutex.withLock {
            if (!force && System.currentTimeMillis() < session.expiresAtMillis - 60_000) return
            val refreshToken = session.refreshToken
                ?: throw TidalAuthException("Not logged in")
            val json = postToken(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
            )
            if (!json.has("access_token")) {
                throw TidalAuthException("Tidal session expired, please log in again")
            }
            session.saveTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
                tokenType = json.optString("token_type", "Bearer"),
                expiresInSec = json.optLong("expires_in", 3600),
            )
        }
    }

    private suspend fun postToken(vararg fields: Pair<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply {
                fields.forEach { (name, value) -> add(name, value) }
            }.build()
            val request = Request.Builder().url("$AUTH_BASE/token").post(body).build()
            http.newCall(request).execute().use { response ->
                JSONObject(response.body?.string() ?: "{}")
            }
        }
}
