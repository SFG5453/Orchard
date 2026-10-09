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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.security.AndroidKeystoreCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

data class OrchardDevice(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeenAt: Long,
    val current: Boolean,
)

data class OrchardAccountState(
    val email: String = "",
    val name: String = "",
    /** This install's id on the account; Orchard Connect announces the device under it. */
    val deviceId: String = "",
    val signingIn: Boolean = false,
    val loadingDevices: Boolean = false,
    val devices: List<OrchardDevice> = emptyList(),
    val error: String = "",
) {
    val signedIn get() = email.isNotBlank()
}

/** Android counterpart of the desktop Orchard account client. No audio data is uploaded here. */
class OrchardAccountService private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("orchard_account", Context.MODE_PRIVATE)
    private val cipher = AndroidKeystoreCipher("orchard_account_refresh_v1")
    private val callbackUri = "${BuildConfig.APPLICATION_ID}://account/callback"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(OrchardAccountState())
    val state = _state.asStateFlow()
    private val tokenLock = Any()
    private var refreshToken = ""
    private var accessToken = ""
    private var accessExpiry = 0L
    @Volatile private var pendingSignIn: PendingSignIn? = null
    private var signInTimeout: Job? = null
    private val generation = AtomicLong()

    private data class PendingSignIn(
        val generation: Long, val state: String, val verifier: String, val startedAt: Long,
    )

    init {
        // The old Supabase credentials have no encore in this account system.
        appContext.getSharedPreferences("orchard_supabase_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        try {
            refreshToken = prefs.getString("refresh_token", "").orEmpty().let {
                if (it.isBlank()) "" else cipher.decrypt(it)
            }
            if (refreshToken.isNotBlank()) {
                _state.value = OrchardAccountState(
                    email = prefs.getString("email", "").orEmpty(),
                    name = prefs.getString("name", "").orEmpty(),
                    deviceId = prefs.getString("device_id", "").orEmpty().ifBlank { deviceIdOf(refreshToken) },
                )
                scope.launch { refreshAccessToken() }
            }
        } catch (_: Exception) {
            // A restored preference without its Keystore key cannot restore a session.
            clearLocalSession()
        }
        val saved = runCatching {
            val value = prefs.getString("pending_sign_in", "").orEmpty()
            if (value.isBlank()) null else JSONObject(cipher.decrypt(value)).let {
                PendingSignIn(it.getLong("generation"), it.getString("state"),
                    it.getString("verifier"), it.getLong("started_at"))
            }
        }.getOrNull()
        if (saved != null && System.currentTimeMillis() - saved.startedAt in 0 until SIGN_IN_TIMEOUT_MS) {
            generation.set(saved.generation)
            pendingSignIn = saved
            _state.value = _state.value.copy(signingIn = true)
            scheduleSignInTimeout(saved)
        } else {
            prefs.edit().remove("pending_sign_in").apply()
        }
    }

    fun signIn() {
        if (_state.value.signingIn) return
        val attempt = generation.incrementAndGet()
        val verifier = randomToken(32)
        val state = randomToken(24)
        val challenge = urlToken(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = "$SERVICE_URL/auth/google/start?redirect_uri=${encode(callbackUri)}" +
            "&state=${encode(state)}&code_challenge=${encode(challenge)}&code_challenge_method=S256"
        val pending = PendingSignIn(attempt, state, verifier, System.currentTimeMillis())
        savePendingSignIn(pending)
        _state.value = _state.value.copy(signingIn = true, error = "")
        scheduleSignInTimeout(pending)
        try {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            cancelSignIn()
            fail(e.message ?: "Could not open browser for sign-in")
        }
    }

    /** Called by MainActivity when the browser returns through this build's callback URI. */
    fun handleAuthCallback(uri: Uri) {
        if (uri.toString().substringBefore('?') != callbackUri) return
        val pending = pendingSignIn ?: return
        if (uri.getQueryParameter("state") != pending.state || generation.get() != pending.generation) return
        savePendingSignIn(null)
        signInTimeout?.cancel()
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            if (uri.getQueryParameter("error") == "access_denied") {
                _state.value = _state.value.copy(signingIn = false, error = "")
            } else {
                fail("Google sign-in failed. Try again.")
            }
            return
        }
        scope.launch {
            try {
                val response = request("POST", "/auth/token", JSONObject().apply {
                    put("grant_type", "authorization_code")
                    put("code", code)
                    put("code_verifier", pending.verifier)
                    put("redirect_uri", callbackUri)
                    put("device", JSONObject().put("name", Build.MODEL).put("platform", "android"))
                })
                if (generation.get() != pending.generation) {
                    // Cancel won the race; a late response must not revive the session.
                    runCatching { request("POST", "/auth/logout",
                        JSONObject().put("refresh_token", response.getString("refresh_token"))) }
                    return@launch
                }
                acceptTokens(response)
                refreshDevices()
            } catch (e: Exception) {
                if (generation.get() == pending.generation) fail(e.message ?: "Could not sign in")
            }
        }
    }

    fun cancelSignIn() {
        generation.incrementAndGet()
        signInTimeout?.cancel()
        savePendingSignIn(null)
        _state.value = _state.value.copy(signingIn = false, error = "")
    }

    private fun savePendingSignIn(pending: PendingSignIn?) {
        pendingSignIn = pending
        val edit = prefs.edit()
        if (pending == null) edit.remove("pending_sign_in") else {
            val value = JSONObject()
                .put("generation", pending.generation)
                .put("state", pending.state)
                .put("verifier", pending.verifier)
                .put("started_at", pending.startedAt)
            edit.putString("pending_sign_in", cipher.encrypt(value.toString()))
        }
        edit.commit()
    }

    private fun scheduleSignInTimeout(pending: PendingSignIn) {
        signInTimeout?.cancel()
        signInTimeout = scope.launch {
            delay((pending.startedAt + SIGN_IN_TIMEOUT_MS - System.currentTimeMillis()).coerceAtLeast(0))
            if (generation.get() == pending.generation && pendingSignIn?.generation == pending.generation) {
                savePendingSignIn(null)
                fail("Sign-in timed out. Try again.")
            }
        }
    }

    fun signOut() {
        cancelSignIn()
        scope.launch {
            val oldToken = synchronized(tokenLock) { refreshToken }
            clearLocalSession()
            if (oldToken.isNotBlank()) {
                runCatching { request("POST", "/auth/logout", JSONObject().put("refresh_token", oldToken)) }
            }
        }
    }

    fun refreshDevices() {
        if (!_state.value.signedIn) return
        val attempt = generation.get()
        _state.value = _state.value.copy(loadingDevices = true, error = "")
        scope.launch {
            try {
                val token = refreshAccessToken() ?: return@launch
                val array = request("GET", "/devices", bearer = token).getJSONArray("devices")
                val devices = (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    OrchardDevice(
                        item.getString("id"), item.optString("name"), item.optString("platform"),
                        item.optLong("last_seen_at"), item.optBoolean("current"),
                    )
                }
                if (generation.get() == attempt) _state.value = _state.value.copy(devices = devices)
            } catch (e: Exception) {
                if (generation.get() == attempt) fail(e.message ?: "Could not load devices")
            } finally {
                if (generation.get() == attempt) _state.value = _state.value.copy(loadingDevices = false)
            }
        }
    }

    fun removeDevice(device: OrchardDevice) {
        if (device.current) {
            signOut()
            return
        }
        scope.launch {
            try {
                val token = refreshAccessToken() ?: return@launch
                request("DELETE", "/devices/${encode(device.id)}", bearer = token)
                refreshDevices()
            } catch (e: Exception) {
                fail(e.message ?: "Could not remove device")
            }
        }
    }

    /** A valid access token, refreshed first when needed; null when signed out or offline. */
    suspend fun accessToken(): String? = refreshAccessToken()

    val serviceUrl: String get() = SERVICE_URL

    private suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        synchronized(tokenLock) {
            if (refreshToken.isBlank()) return@synchronized null
            if (accessToken.isNotBlank() && System.currentTimeMillis() < accessExpiry) return@synchronized accessToken
            try {
                val response = request("POST", "/auth/token", JSONObject()
                    .put("grant_type", "refresh_token").put("refresh_token", refreshToken))
                acceptTokens(response)
                accessToken
            } catch (e: AccountHttpException) {
                if (e.status == 400 && e.code == "invalid_grant") {
                    clearLocalSession()
                    fail("You were signed out of Orchard. Sign in again.")
                } else fail(e.message ?: "Could not refresh account")
                null
            } catch (_: Exception) {
                // Keep the refresh token during network outages.
                null
            }
        }
    }

    private fun acceptTokens(json: JSONObject) {
        val refresh = json.getString("refresh_token")
        val encrypted = cipher.encrypt(refresh)
        val user = json.getJSONObject("user")
        synchronized(tokenLock) {
            // Commit the rotated token before another refresh can start.
            prefs.edit().putString("refresh_token", encrypted)
                .putString("email", user.optString("email"))
                .putString("name", user.optString("name"))
                .putString("device_id", json.optString("device_id")).commit()
            refreshToken = refresh
            accessToken = json.getString("access_token")
            accessExpiry = System.currentTimeMillis() + (json.getLong("expires_in") - 60).coerceAtLeast(0) * 1000
        }
        _state.value = _state.value.copy(
            email = user.optString("email"), name = user.optString("name"),
            deviceId = json.optString("device_id").ifBlank { deviceIdOf(refresh) }, signingIn = false, error = "",
        )
    }

    private fun clearLocalSession() {
        synchronized(tokenLock) {
            refreshToken = ""
            accessToken = ""
            accessExpiry = 0
            prefs.edit().clear().apply()
        }
        _state.value = OrchardAccountState()
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(error = message, signingIn = false)
    }

    private fun request(method: String, path: String, body: JSONObject? = null, bearer: String? = null): JSONObject {
        val connection = (URL("$SERVICE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (status !in 200..299) throw AccountHttpException(
                status, json.optString("error"), json.optString("error_description", "Account service returned HTTP $status"),
            )
            json
        } finally {
            connection.disconnect()
        }
    }

    private class AccountHttpException(val status: Int, val code: String, message: String) : Exception(message)

    companion object {
        private const val SERVICE_URL = "https://account.sfg545.dev"
        private const val SIGN_IN_TIMEOUT_MS = 300_000L
        @Volatile private var instance: OrchardAccountService? = null

        fun get(context: Context): OrchardAccountService = instance ?: synchronized(this) {
            instance ?: OrchardAccountService(context).also { instance = it }
        }

        private fun urlToken(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun randomToken(size: Int): String = ByteArray(size).also(SecureRandom()::nextBytes).let(::urlToken)
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        // Refresh tokens are "<device id>.<secret>"; older installs never stored the id itself.
        private fun deviceIdOf(refreshToken: String): String = refreshToken.substringBefore('.', "")
    }
}
