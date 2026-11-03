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

package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.applemusic.AppleMusicAuth
import dev.sfg.orchard.mobile.auth.OrchardAccountService

@Composable
internal fun appleMusicConnectionStatus(): ConnectionStatus {
    val context = LocalContext.current
    val auth = remember(context) { AppleMusicAuth.get(context) }
    val state by auth.state.collectAsStateWithLifecycle()
    return ConnectionStatus(state.connected, when {
        state.connected -> "Connected"
        state.error.isNotBlank() -> state.error
        else -> ConnectionService.AppleMusic.blurb
    })
}

@Composable
internal fun AppleMusicSettingsCard(onConnect: () -> Unit) {
    val context = LocalContext.current
    val auth = remember(context) { AppleMusicAuth.get(context) }
    val state by auth.state.collectAsStateWithLifecycle()
    val account = remember(context) { OrchardAccountService.get(context) }
    val accountState by account.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (BuildConfig.APPLE_MUSIC_LOGO) AppleMusicLogo(Modifier.size(44.dp))
            Text("Apple Music", style = MaterialTheme.typography.titleMedium, color = SettingsStyle.Title)
        }
        Text(
            if (state.connected) "Your Apple Music account is connected to Orchard."
            else "Sign in to connect Orchard to your Apple Music account. An Apple Music subscription is required.",
            style = MaterialTheme.typography.bodyMedium, color = SettingsStyle.Description,
        )
        if (state.error.isNotBlank()) {
            Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        when {
            state.connected -> IntegrationAction("Disconnect", destructive = true, onClick = auth::disconnect)
            // The worker only issues developer tokens to Orchard sessions.
            !accountState.signedIn -> Text(
                "Sign in to your Orchard account first, under Orchard Account in Connections.",
                style = MaterialTheme.typography.bodySmall, color = SettingsStyle.Description,
            )
            else -> IntegrationAction("Sign in to Apple Music", onClick = onConnect)
        }
    }
}
