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

package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.auth.OrchardAccountService
import dev.sfg.orchard.mobile.support.SupportService
import java.text.DateFormat
import java.util.Date

@Composable
fun OrchardAccountSettingsCard() {
    val context = LocalContext.current
    val account = remember { OrchardAccountService.get(context) }
    val state by account.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) account.refreshDevices()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            if (state.signedIn) state.name.ifBlank { state.email } else
                "Sign in with Google to use your Orchard account on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.signedIn) {
            Text(state.email, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.error.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(state.error, color = Color(0xFFFF7777), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.signingIn -> {
                Text("Finish signing in in your browser.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                SettingsPill("Cancel", onClick = account::cancelSignIn, modifier = Modifier.fillMaxWidth())
            }
            state.signedIn -> {
                SettingsPill("Sign out", onClick = account::signOut,
                    modifier = Modifier.fillMaxWidth(), destructive = true)
                Spacer(Modifier.height(20.dp))
                GithubLinkRow()
                Spacer(Modifier.height(20.dp))
                Text("Devices", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (state.loadingDevices && state.devices.isEmpty()) {
                    Text("Loading devices…", style = MaterialTheme.typography.bodySmall)
                }
                state.devices.forEach { device ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name + if (device.current) " · This device" else "")
                            val lastSeen = DateFormat.getDateInstance().format(Date(device.lastSeenAt * 1000))
                            Text("${device.platform} · Last seen $lastSeen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!device.current) {
                            SettingsPill("Remove", onClick = { account.removeDevice(device) })
                        }
                    }
                }
            }
            else -> SettingsPill("Sign in with Google", onClick = account::signIn,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Bug reports become GitHub issues in your name, so they need this link. */
@Composable
private fun GithubLinkRow() {
    val context = LocalContext.current
    val support = remember { SupportService.get(context) }
    val state by support.state.collectAsStateWithLifecycle()
    val github = state.github
    Text("GitHub", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        when {
            github != null -> "@${github.login} · Used for bug reports"
            state.linking -> "Finish linking in your browser."
            else -> "Link GitHub to send bug reports from Orchard."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    when {
        github != null -> SettingsPill("Unlink GitHub", onClick = support::unlinkGithub, modifier = Modifier.fillMaxWidth())
        state.linking -> SettingsPill("Cancel", onClick = support::cancelGithubLink, modifier = Modifier.fillMaxWidth())
        else -> SettingsPill("Link GitHub", onClick = support::linkGithub, modifier = Modifier.fillMaxWidth())
    }
}
