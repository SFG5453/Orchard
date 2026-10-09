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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Live values the category rows summarise. Built once by the screen so the hub stays dumb. */
internal data class SettingsSummaries(
    val connectedCount: Int,
    val storage: String,
)

/** Top level of settings: search, account, quick switches and one row per page. */
@Composable
internal fun SettingsHub(
    settings: OrchardSettings,
    auth: AuthState,
    summaries: SettingsSummaries,
    onSettings: (OrchardSettings) -> Unit,
    onAutoplayEnabled: ((Boolean) -> Unit)?,
    onOpen: (SettingsPage) -> Unit,
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignOut: () -> Unit,
    onWelcome: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    SettingsSearchField(query) { query = it }

    if (query.isNotBlank()) {
        SearchResults(query, onOpen)
        return
    }

    Column(Modifier.padding(top = 16.dp)) { AccountCard(auth, onSignIn, onSwitchAccount, onSignOut) }

    SectionLabel("Quick switches", 2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickTile(Icons.Rounded.Loop, "Autoplay", settings.autoplayEnabled, Modifier.weight(1f)) { enabled ->
                onAutoplayEnabled?.invoke(enabled) ?: onSettings(settings.copy(autoplayEnabled = enabled))
            }
            QuickTile(Icons.Rounded.Waves, "Crossfade", settings.crossfadeEnabled, Modifier.weight(1f)) {
                onSettings(settings.copy(crossfadeEnabled = it))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickTile(Icons.AutoMirrored.Rounded.VolumeUp, "Normalize volume", settings.volumeNormalizationEnabled, Modifier.weight(1f)) {
                onSettings(settings.copy(volumeNormalizationEnabled = it))
            }
            QuickTile(Icons.Rounded.Image, "Animated artwork", settings.animatedArtwork, Modifier.weight(1f)) {
                onSettings(settings.copy(animatedArtwork = it))
            }
        }
    }

    SectionLabel("All settings", 4)
    SettingsPanel(index = 5) {
        val quality = settings.audioQuality.label
        val eq = if (settings.equalizerConfig.enabled) "on" else "off"
        ActionRow(
            title = SettingsPage.Audio.title,
            subtitle = "Quality: $quality · Equalizer $eq",
            icon = SettingsPage.Audio.icon,
            onClick = { onOpen(SettingsPage.Audio) },
        )
        PanelDivider()
        ActionRow(
            title = SettingsPage.Appearance.title,
            subtitle = (if (settings.useSystemColors) "System colours" else "Orchard green") + " · Home layout, player",
            icon = SettingsPage.Appearance.icon,
            onClick = { onOpen(SettingsPage.Appearance) },
        )
        PanelDivider()
        ActionRow(
            title = SettingsPage.Connections.title,
            subtitle = "Last.fm, Qobuz, Discord and more",
            icon = SettingsPage.Connections.icon,
            value = summaries.connectedCount.takeIf { it > 0 }?.let { "$it of ${ConnectionService.entries.size}" },
            onClick = { onOpen(SettingsPage.Connections) },
        )
        PanelDivider()
        ActionRow(
            title = SettingsPage.Storage.title,
            subtitle = summaries.storage,
            icon = SettingsPage.Storage.icon,
            onClick = { onOpen(SettingsPage.Storage) },
        )
    }

    Column(Modifier.padding(top = 16.dp)) {
        SettingsPanel(index = 6) {
            val support = dev.sfg.orchard.mobile.ui.support.LocalSupportUi.current
            if (support != null) {
                val unread = support.service.state.collectAsStateWithLifecycle().value.unread
                ActionRow(
                    title = "Report a bug",
                    subtitle = if (unread > 0) "Your reports have news" else "Send a bug, idea or feedback with a screenshot",
                    icon = Icons.Rounded.BugReport,
                    value = unread.takeIf { it > 0 }?.toString(),
                    onClick = support::open,
                )
                PanelDivider()
            }
            ActionRow(
                title = "Welcome & setup walkthrough",
                subtitle = "Revisit the initial setup guide",
                icon = Icons.Rounded.Explore,
                onClick = onWelcome,
            )
        }
    }
}

@Composable
private fun SearchResults(query: String, onOpen: (SettingsPage) -> Unit) {
    val results = searchSettings(query)
    Column(Modifier.padding(top = 16.dp)) {
        if (results.isEmpty()) {
            Text(
                "No settings match \"${query.trim()}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = SettingsStyle.Description,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
            )
            return
        }
        SettingsPanel {
            results.forEachIndexed { index, entry ->
                if (index > 0) PanelDivider()
                ActionRow(
                    title = entry.title,
                    subtitle = "${entry.page.title} · ${entry.subtitle}",
                    icon = entry.page.icon,
                    onClick = { onOpen(entry.page) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSearchField(query: String, onQuery: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = TextStyle(color = SettingsStyle.Title, fontSize = 15.sp),
        cursorBrush = SolidColor(SettingsStyle.SageSoft),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Search settings" },
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(SettingsStyle.Panel, shape)
                    .border(1.dp, SettingsStyle.PanelBorder, shape)
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = SettingsStyle.Description, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Search settings", color = SettingsStyle.Description, fontSize = 15.sp)
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(48.dp).clickable(role = Role.Button) { onQuery("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = SettingsStyle.Description, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

/** Switch tile: lit with the sage selection gradient when on, plain glass when off. */
@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val lit = Brush.verticalGradient(listOf(Color(0x338CC4A0), Color(0x1A8CC4A0)))
    Column(
        modifier
            .clip(shape)
            .background(if (on) lit else SolidColor(SettingsStyle.Panel))
            .border(1.dp, if (on) Color(0x38A0D6B4) else SettingsStyle.PanelBorder, shape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .defaultMinSize(minHeight = 96.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (on) SettingsStyle.SageSoft else SettingsStyle.Description,
            modifier = Modifier.size(22.dp),
        )
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = SettingsStyle.Title,
            )
            Text(
                if (on) "On" else "Off",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = SettingsStyle.Description,
            )
        }
    }
}

/** Signed-in identity with switch and sign-out actions. */
@Composable
private fun AccountCard(
    auth: AuthState,
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
    SettingsPanel(index = 1) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val avatarUrl = (auth as? AuthState.SignedIn)?.avatarUrl.orEmpty()
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(SettingsStyle.Tile)
                        .border(1.dp, SettingsStyle.TileBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = SettingsStyle.TileIcon, modifier = Modifier.size(26.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    when (auth) {
                        AuthState.Restoring -> AccountText("Checking session…", "One moment")
                        AuthState.Authorizing -> AccountText("Completing sign-in…", "One moment")
                        AuthState.SignedOut -> AccountText("Not signed in", "Sync your library and recommendations")
                        // displayName falls back to the literal "YouTube Music", so the subtitle must not repeat it.
                        is AuthState.SignedIn -> AccountText(auth.displayName, "Signed in")
                        is AuthState.Error -> AccountText("Sign-in failed", auth.message, CanopyColors.Danger)
                    }
                }
            }
            when (auth) {
                AuthState.SignedOut -> SettingsPill("Sign in", onSignIn, Modifier.fillMaxWidth())
                is AuthState.Error -> SettingsPill("Try again", onSignIn, Modifier.fillMaxWidth())
                is AuthState.SignedIn -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsPill("Switch account", onSwitchAccount, Modifier.weight(1f))
                    SettingsPill("Sign out", onSignOut, Modifier.weight(1f), destructive = true)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun AccountText(title: String, subtitle: String, titleColor: Color = SettingsStyle.Title) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        color = titleColor,
    )
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
        color = SettingsStyle.Description,
    )
}
