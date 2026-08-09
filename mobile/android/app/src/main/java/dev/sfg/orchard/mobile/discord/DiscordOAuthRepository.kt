/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.discord

import android.content.Context
import android.util.Log
import java.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

private val Context.discordAuthDataStore: DataStore<Preferences> by preferencesDataStore(name = "discord_auth")

/**
 * Handles Discord OAuth2 PKCE login, token refresh, and account state.
 */
class DiscordOAuthRepository(
    context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val store = context.applicationContext.discordAuthDataStore
    private val mutableAuthState = MutableStateFlow<DiscordAuthState>(DiscordAuthState.SignedOut)
    val authState: StateFlow<DiscordAuthState> = mutableAuthState.asStateFlow()

    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null

    init {
        scope.launch { restore() }
    }

    suspend fun restore() = withContext(Dispatchers.IO) {
        val prefs = runCatching { store.data.first() }.getOrDefault(emptyPreferences())
        val accessToken = prefs[ACCESS_TOKEN].orEmpty()
        val refreshToken = prefs[REFRESH_TOKEN].orEmpty()
        val expiresAt = prefs[EXPIRES_AT] ?: 0L

        if (accessToken.isBlank() || refreshToken.isBlank()) {
            mutableAuthState.value = DiscordAuthState.SignedOut
            return@withContext
        }

        val account = prefs[ACCOUNT_JSON]?.let { runCatching { parseAccount(JSONObject(it)) }.getOrNull() }
        var session = DiscordAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMs = expiresAt,
            account = account,
        )

        if (session.isExpired) {
            val refreshed = refreshAccessToken(session.refreshToken)
            if (refreshed != null) {
                session = refreshed
            } else {
                Log.w(TAG, "Failed to refresh expired Discord token on startup; signing out")
                clearSession()
                return@withContext
            }
        }

        if (session.account == null) {
            val fetchedAccount = fetchAccount(session.accessToken)
            if (fetchedAccount != null) {
                session = session.copy(account = fetchedAccount)
                saveSession(session)
            }
        }

        mutableAuthState.value = DiscordAuthState.SignedIn(session)
    }

    fun buildAuthorizationUrl(): String {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()

        pendingCodeVerifier = verifier
        pendingState = state
        mutableAuthState.value = DiscordAuthState.Authorizing

        val encodedRedirect = URLEncoder.encode(DISCORD_REDIRECT_URI, "UTF-8")
        val encodedScopes = URLEncoder.encode(DISCORD_OAUTH_SCOPES, "UTF-8")
        val encodedChallenge = URLEncoder.encode(challenge, "UTF-8")
        val encodedState = URLEncoder.encode(state, "UTF-8")

        return "https://discord.com/oauth2/authorize" +
            "?client_id=$DISCORD_APPLICATION_ID" +
            "&response_type=code" +
            "&redirect_uri=$encodedRedirect" +
            "&scope=$encodedScopes" +
            "&code_challenge=$encodedChallenge" +
            "&code_challenge_method=S256" +
            "&state=$encodedState"
    }

    suspend fun handleAuthorizationCode(code: String, state: String?): Boolean = withContext(Dispatchers.IO) {
        val verifier = pendingCodeVerifier
        if (verifier == null) {
            Log.e(TAG, "No pending code verifier found for Discord authorization")
            mutableAuthState.value = DiscordAuthState.Error("Authentication session expired")
            return@withContext false
        }
        if (pendingState != null && pendingState != state) {
            Log.e(TAG, "Discord OAuth state mismatch")
            mutableAuthState.value = DiscordAuthState.Error("Authentication state mismatch")
            return@withContext false
        }

        pendingCodeVerifier = null
        pendingState = null

        val formBody = FormBody.Builder()
            .add("client_id", DISCORD_APPLICATION_ID)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", DISCORD_REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url("https://discord.com/api/oauth2/token")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "Discord token exchange failed: HTTP ${response.code}"
                    Log.e(TAG, err)
                    mutableAuthState.value = DiscordAuthState.Error(err)
                    return@withContext false
                }
                val body = response.body.string()
                val json = JSONObject(body)
                val accessToken = json.getString("access_token")
                val refreshToken = json.getString("refresh_token")
                val expiresInSec = json.optLong("expires_in", 604800L)
                val expiresAt = System.currentTimeMillis() + (expiresInSec * 1000L)

                val account = fetchAccount(accessToken)
                val session = DiscordAuthSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtEpochMs = expiresAt,
                    account = account,
                )
                saveSession(session)
                mutableAuthState.value = DiscordAuthState.SignedIn(session)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exchange Discord authorization code", e)
            mutableAuthState.value = DiscordAuthState.Error(e.message ?: "Authorization failed")
            false
        }
    }

    suspend fun getValidSession(): DiscordAuthSession? = withContext(Dispatchers.IO) {
        val current = (mutableAuthState.value as? DiscordAuthState.SignedIn)?.session ?: return@withContext null
        if (!current.isExpired) return@withContext current

        val refreshed = refreshAccessToken(current.refreshToken)
        if (refreshed != null) {
            mutableAuthState.value = DiscordAuthState.SignedIn(refreshed)
            refreshed
        } else {
            mutableAuthState.value = DiscordAuthState.SignedOut
            null
        }
    }

    /**
     * Renews the access token even when it still looks valid by the clock, for the
     * case where Discord has rejected it outright. Returns the new access token.
     */
    suspend fun forceRefresh(): String? = withContext(Dispatchers.IO) {
        val current = (mutableAuthState.value as? DiscordAuthState.SignedIn)?.session ?: return@withContext null
        val refreshed = refreshAccessToken(current.refreshToken)
        if (refreshed == null) {
            mutableAuthState.value = DiscordAuthState.SignedOut
            return@withContext null
        }
        mutableAuthState.value = DiscordAuthState.SignedIn(refreshed)
        refreshed.accessToken
    }

    suspend fun refreshAccessToken(refreshToken: String): DiscordAuthSession? = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", DISCORD_APPLICATION_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url("https://discord.com/api/oauth2/token")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Discord token refresh failed: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body.string()
                val json = JSONObject(body)
                val newAccessToken = json.getString("access_token")
                val newRefreshToken = json.getString("refresh_token")
                val expiresInSec = json.optLong("expires_in", 604800L)
                val expiresAt = System.currentTimeMillis() + (expiresInSec * 1000L)

                val currentAccount = (mutableAuthState.value as? DiscordAuthState.SignedIn)?.session?.account
                val account = currentAccount ?: fetchAccount(newAccessToken)

                val session = DiscordAuthSession(
                    accessToken = newAccessToken,
                    refreshToken = newRefreshToken,
                    expiresAtEpochMs = expiresAt,
                    account = account,
                )
                saveSession(session)
                session
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Discord token refresh", e)
            null
        }
    }

    suspend fun fetchAccount(accessToken: String): DiscordAccount? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://discord.com/api/v10/users/@me")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch Discord account: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body.string()
                parseAccount(JSONObject(body))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception while fetching Discord user account", e)
            null
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        clearSession()
        mutableAuthState.value = DiscordAuthState.SignedOut
    }

    private suspend fun saveSession(session: DiscordAuthSession) {
        store.edit { prefs ->
            prefs[ACCESS_TOKEN] = session.accessToken
            prefs[REFRESH_TOKEN] = session.refreshToken
            prefs[EXPIRES_AT] = session.expiresAtEpochMs
            session.account?.let { account ->
                prefs[ACCOUNT_JSON] = JSONObject().apply {
                    put("id", account.id)
                    put("username", account.username)
                    if (account.globalName != null) put("global_name", account.globalName)
                    if (account.avatar != null) put("avatar", account.avatar)
                    if (account.discriminator != null) put("discriminator", account.discriminator)
                }.toString()
            } ?: prefs.remove(ACCOUNT_JSON)
        }
    }

    private suspend fun clearSession() {
        store.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(EXPIRES_AT)
            prefs.remove(ACCOUNT_JSON)
        }
    }

    private fun parseAccount(json: JSONObject): DiscordAccount {
        return DiscordAccount(
            id = json.getString("id"),
            username = json.getString("username"),
            globalName = json.optString("global_name").takeIf(String::isNotBlank),
            avatar = json.optString("avatar").takeIf(String::isNotBlank),
            discriminator = json.optString("discriminator").takeIf(String::isNotBlank),
        )
    }

    companion object {
        private const val TAG = "DiscordOAuth"
        val ACCESS_TOKEN = stringPreferencesKey("discord_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("discord_refresh_token")
        val EXPIRES_AT = longPreferencesKey("discord_expires_at")
        val ACCOUNT_JSON = stringPreferencesKey("discord_account_json")

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun generateCodeChallenge(verifier: String): String {
            val bytes = verifier.toByteArray(StandardCharsets.US_ASCII)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun generateState(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
