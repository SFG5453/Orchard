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

package dev.sfg.orchard.mobile.ui.support

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.auth.OrchardAccountService
import dev.sfg.orchard.mobile.support.SupportReport
import dev.sfg.orchard.mobile.support.SupportState
import dev.sfg.orchard.mobile.ui.screens.ActionRow
import dev.sfg.orchard.mobile.ui.screens.PanelDivider
import dev.sfg.orchard.mobile.ui.screens.SettingsPanel
import dev.sfg.orchard.mobile.ui.screens.SettingsPill
import dev.sfg.orchard.mobile.ui.screens.SettingsStyle

/** Full-screen report sheet: gate, composer, report list or one report's timeline. */
@Composable
internal fun SupportSheet(ui: SupportUi, page: String) {
    val context = LocalContext.current
    val account = remember { OrchardAccountService.get(context) }
    val accountState by account.state.collectAsStateWithLifecycle()
    val state by ui.service.state.collectAsStateWithLifecycle()
    val ready = accountState.signedIn && state.github != null

    val back = {
        when {
            !ready || ui.screen == SupportScreen.Compose -> ui.close()
            ui.screen == SupportScreen.Report -> {
                ui.service.closeReport()
                ui.screen = SupportScreen.List
            }
            else -> ui.screen = SupportScreen.Compose
        }
    }
    BackHandler(onBack = back)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF70E1210))
            // Taps on empty space stop here instead of reaching the screen underneath.
            .pointerInput(Unit) { detectTapGestures {} }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val atRoot = !ready || ui.screen == SupportScreen.Compose
            IconButton(if (atRoot) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                if (atRoot) "Close" else "Back", back)
            Text(
                when {
                    !ready -> "Report a bug"
                    ui.screen == SupportScreen.List -> "Your reports"
                    ui.screen == SupportScreen.Report -> "Report #${state.active?.number ?: ""}"
                    else -> "New report"
                },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = SettingsStyle.Title,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (ready && ui.screen == SupportScreen.Compose) {
                ReportsButton(unread = state.unread, onClick = { ui.screen = SupportScreen.List })
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !accountState.signedIn -> Gate(
                    title = "Sign in to report a bug",
                    text = "Reports go through your Orchard account, so Orchard can tell you when someone replies, a fix lands, or the report closes.",
                    action = if (accountState.signingIn) "Cancel" else "Sign in with Google",
                    onAction = if (accountState.signingIn) account::cancelSignIn else account::signIn,
                    error = accountState.error,
                )
                state.github == null -> Gate(
                    title = "Link your GitHub account",
                    text = "Reports become public GitHub issues under your name, so maintainers can ask you questions. Orchard only reads your GitHub username and avatar.",
                    action = if (state.linking) "Cancel" else "Link GitHub",
                    onAction = if (state.linking) ui.service::cancelGithubLink else ui.service::linkGithub,
                    hint = if (state.linking) "Finish in your browser. Orchard picks it up on its own." else "",
                    error = state.error,
                )
                ui.screen == SupportScreen.List -> ReportList(state, onNew = { ui.screen = SupportScreen.Compose },
                    onOpen = { ui.openReport(it) })
                ui.screen == SupportScreen.Report -> SupportTimeline(state)
                else -> SupportComposer(
                    state = state,
                    service = ui.service,
                    page = page,
                    onCapture = { ui.mode = SupportMode.Capturing },
                    onSent = { ui.screen = SupportScreen.Report },
                )
            }
        }
    }
}

@Composable
private fun IconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = SettingsStyle.Title, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ReportsButton(unread: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Inbox, contentDescription = null, tint = SettingsStyle.SageSoft, modifier = Modifier.size(18.dp))
        Text("Reports", color = SettingsStyle.Title, fontSize = 14.sp)
        if (unread > 0) UnreadDot()
    }
}

@Composable
internal fun UnreadDot() {
    Box(Modifier.size(8.dp).clip(CircleShape).background(SettingsStyle.Sage))
}

@Composable
private fun Gate(title: String, text: String, action: String, onAction: () -> Unit, hint: String = "", error: String = "") {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Rounded.BugReport, contentDescription = null, tint = SettingsStyle.SageSoft, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = SettingsStyle.Title, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SettingsStyle.Description, textAlign = TextAlign.Center)
        SettingsPill(action, onClick = onAction, modifier = Modifier.fillMaxWidth())
        if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = SettingsStyle.Caption)
        if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE8A598), textAlign = TextAlign.Center)
    }
}

internal fun stateLabel(report: SupportReport): String = when {
    !report.closed -> "Open"
    report.stateReason == "not_planned" -> "Not planned"
    report.stateReason == "duplicate" -> "Duplicate"
    else -> "Closed"
}

@Composable
private fun ReportList(state: SupportState, onNew: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        SettingsPanel {
            ActionRow(title = "New report", subtitle = "Bug, idea or feedback", onClick = onNew, icon = Icons.Rounded.Add)
        }
        Column(Modifier.padding(top = 16.dp)) {
            if (state.reports.isEmpty()) {
                Text(
                    if (state.loaded) "No reports yet. Everything working is also a fine outcome." else "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SettingsStyle.Description,
                    modifier = Modifier.padding(8.dp),
                )
            } else SettingsPanel {
                state.reports.forEachIndexed { index, report ->
                    if (index > 0) PanelDivider()
                    ReportRow(report, onClick = { onOpen(report.id) })
                }
            }
        }
        Text(
            "Linked as @${state.github?.login.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = SettingsStyle.Caption,
            modifier = Modifier.padding(8.dp, 16.dp),
        )
    }
}

@Composable
private fun ReportRow(report: SupportReport, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                report.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    fontWeight = if (report.unread > 0) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = SettingsStyle.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val latest = report.latest?.title?.let { " · $it" }.orEmpty()
            Text(
                "#${report.number} · ${stateLabel(report)}$latest",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = SettingsStyle.Description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (report.unread > 0) UnreadDot()
    }
}
