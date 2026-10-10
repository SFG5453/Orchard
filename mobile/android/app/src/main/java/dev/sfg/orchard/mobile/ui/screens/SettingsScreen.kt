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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.MobileChangelog
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.UpdateState
import dev.sfg.orchard.mobile.auth.AuthState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.auth.OrchardAccountService
import dev.sfg.orchard.mobile.discord.DiscordPresenceStatus
import dev.sfg.orchard.mobile.lastfm.LastfmState
import dev.sfg.orchard.mobile.listenbrainz.ListenBrainzState
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.settings.CacheManager
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.components.ReleaseNotesDialog
import dev.sfg.orchard.mobile.ui.components.UpdateDialog
import dev.sfg.orchard.mobile.ui.scroll.orchardVerticalScroll as verticalScroll
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
fun SettingsScreen(
    settings: OrchardSettings,
    auth: AuthState,
    discordStatus: DiscordPresenceStatus = DiscordPresenceStatus.Ready,
    lastfmState: LastfmState = LastfmState.SignedOut,
    listenBrainzState: ListenBrainzState = ListenBrainzState.SignedOut,
    updateState: UpdateState = UpdateState.Idle,
    onSettings: (OrchardSettings) -> Unit,
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignOut: () -> Unit,
    onConnectLastfm: () -> Unit = {},
    onCompleteLastfm: () -> Unit = {},
    onDisconnectLastfm: () -> Unit = {},
    onConnectListenBrainz: (String) -> Unit = {},
    onDisconnectListenBrainz: () -> Unit = {},
    onConnectSpotify: () -> Unit = {},
    qobuzStatus: dev.sfg.orchard.mobile.qobuz.QobuzStatus = dev.sfg.orchard.mobile.qobuz.QobuzStatus(),
    onConnectQobuz: () -> Unit = {},
    onDisconnectQobuz: () -> Unit = {},
    onQobuzEnabledChange: (Boolean) -> Unit = {},
    onQobuzQualityChange: (dev.sfg.orchard.mobile.qobuz.QobuzQuality) -> Unit = {},
    onWelcome: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onInstallUpdate: (MobileUpdateMetadata) -> Unit = {},
    onHomeLayout: () -> Unit = {},
    /**
     * Separate from [onSettings] because switching Autoplay off also strips the tracks it added,
     * which only the view model can do.
     */
    onAutoplayEnabled: ((Boolean) -> Unit)? = null,
    cacheSizeBytes: Long = 0L,
    isClearingCache: Boolean = false,
    onClearCache: () -> Unit = {},
    downloadedBytes: Long = 0L,
    onDeleteAllDownloads: () -> Unit = {},
    onRefreshCacheSize: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    var connection by rememberSaveable { mutableStateOf<ConnectionService?>(null) }
    var showNotesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefreshCacheSize()
    }
    // Back climbs one level (detail, then page, then hub) before it leaves settings.
    val goBack: () -> Unit = {
        if (connection != null) connection = null else page = SettingsPage.Home
    }
    BackHandler(enabled = page != SettingsPage.Home, onBack = goBack)

    val availableUpdate = (updateState as? UpdateState.Available)?.metadata
    val context = LocalContext.current
    val account = remember { OrchardAccountService.get(context) }
    val accountState by account.state.collectAsStateWithLifecycle()
    val statuses = connectionStatuses(
        settings, discordStatus, lastfmState, listenBrainzState, qobuzStatus, accountState.email,
    )
    val summaries = SettingsSummaries(
        connectedCount = statuses.values.count { it.connected },
        storage = "${CacheManager.formatStorageSize(cacheSizeBytes)} cached · " + when {
            availableUpdate != null -> "Update available"
            !BuildConfig.UPDATER_ENABLED -> "Updates disabled"
            else -> "Up to date"
        },
    )

    CompositionLocalProvider(LocalAccent provides SettingsStyle.Sage) {
        // No fill: the app-level cover backdrop shows through the panels.
        Column(Modifier.fillMaxSize()) {
            SettingsHeader(
                title = connection?.title ?: page.title,
                blurb = if (connection != null) "" else page.blurb,
                showBack = page != SettingsPage.Home,
                chip = connection?.let { statuses[it] },
                onBack = goBack,
            )
            AnimatedContent(
                targetState = page to connection,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "SettingsPage",
                modifier = Modifier.weight(1f),
            ) { (current, detail) ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    when (current) {
                        SettingsPage.Home -> SettingsHub(
                            settings = settings,
                            auth = auth,
                            summaries = summaries,
                            onSettings = onSettings,
                            onAutoplayEnabled = onAutoplayEnabled,
                            onOpen = {
                                connection = null
                                page = it
                            },
                            onSignIn = onSignIn,
                            onSwitchAccount = onSwitchAccount,
                            onSignOut = onSignOut,
                            onWelcome = onWelcome,
                        )
                        SettingsPage.Audio -> AudioPage(settings, onSettings, onAutoplayEnabled)
                        SettingsPage.Appearance -> AppearancePage(settings, onSettings, onHomeLayout)
                        SettingsPage.Connections -> if (detail == null) {
                            ConnectionsList(statuses) { connection = it }
                        } else {
                            ConnectionDetail(
                                service = detail,
                                settings = settings,
                                discordStatus = discordStatus,
                                lastfmState = lastfmState,
                                listenBrainzState = listenBrainzState,
                                qobuzStatus = qobuzStatus,
                                onSettings = onSettings,
                                onConnectLastfm = onConnectLastfm,
                                onCompleteLastfm = onCompleteLastfm,
                                onDisconnectLastfm = onDisconnectLastfm,
                                onConnectListenBrainz = onConnectListenBrainz,
                                onDisconnectListenBrainz = onDisconnectListenBrainz,
                                onConnectSpotify = onConnectSpotify,
                                onConnectQobuz = onConnectQobuz,
                                onDisconnectQobuz = onDisconnectQobuz,
                                onQobuzEnabledChange = onQobuzEnabledChange,
                                onQobuzQualityChange = onQobuzQualityChange,
                            )
                        }
                        SettingsPage.Storage -> StoragePage(
                            settings = settings,
                            updateState = updateState,
                            cacheSizeBytes = cacheSizeBytes,
                            isClearingCache = isClearingCache,
                            onSettings = onSettings,
                            onClearCache = onClearCache,
                            downloadedBytes = downloadedBytes,
                            onDeleteAllDownloads = onDeleteAllDownloads,
                            onCheckForUpdates = onCheckForUpdates,
                            onInstallUpdate = onInstallUpdate,
                            onShowNotes = { showNotesDialog = true },
                        )
                    }
                    if (current == SettingsPage.Home) {
                        Spacer(Modifier.height(28.dp))
                        val versionText = if (BuildConfig.CODENAME.isNotBlank()) {
                            "Orchard Mobile ${BuildConfig.VERSION_NAME} \"${BuildConfig.CODENAME}\""
                        } else {
                            "Orchard Mobile ${BuildConfig.VERSION_NAME}"
                        }
                        Text(
                            versionText,
                            color = SettingsStyle.Caption,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(OrchardChromeHeight + 16.dp))
                }
            }
        }
    }

    if (showNotesDialog) {
        if (availableUpdate != null) {
            UpdateDialog(
                state = updateState,
                onInstall = {
                    showNotesDialog = false
                    onInstallUpdate(it)
                },
                onDismiss = { showNotesDialog = false },
            )
        } else {
            ReleaseNotesDialog(
                version = BuildConfig.VERSION_NAME,
                codename = BuildConfig.CODENAME,
                releaseNotes = MobileChangelog.CURRENT_RELEASE_NOTES,
                onDismiss = { showNotesDialog = false },
            )
        }
    }
}

/** Title with a back button on sub-pages, plus an optional blurb and connection chip. */
@Composable
private fun SettingsHeader(
    title: String,
    blurb: String,
    showBack: Boolean,
    chip: ConnectionStatus?,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        if (!showBack) {
            Spacer(Modifier.height(8.dp))
        } else {
            Box(
                Modifier
                    .offset(x = (-8).dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = SettingsStyle.Title)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
            color = SettingsStyle.Title,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (blurb.isNotEmpty()) {
            Text(
                blurb,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = SettingsStyle.Description,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
        if (chip != null) {
            ConnectionChip(chip, Modifier.padding(start = 4.dp, top = 10.dp))
        }
    }
}
