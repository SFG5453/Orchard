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

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import dev.sfg.orchard.mobile.support.SupportEvent
import dev.sfg.orchard.mobile.support.SupportState
import dev.sfg.orchard.mobile.ui.screens.SettingsStyle

private fun eventIcon(kind: String) = when (kind) {
    "comment" -> Icons.Rounded.ChatBubbleOutline
    "commit" -> Icons.Rounded.Commit
    "pull_request", "mention" -> Icons.Rounded.Link
    "closed" -> Icons.Rounded.CheckCircle
    "reopened" -> Icons.Rounded.Refresh
    "assigned" -> Icons.Rounded.AccountCircle
    else -> Icons.Rounded.Info
}

/** One report's GitHub activity, newest at the bottom like the issue page. */
@Composable
internal fun SupportTimeline(state: SupportState) {
    val context = LocalContext.current
    val report = state.active ?: return
    val list = rememberLazyListState()
    val open = { url: String ->
        if (url.isNotBlank()) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
    LaunchedEffect(state.events.size) {
        if (state.events.isNotEmpty()) list.scrollToItem(state.events.size)
    }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    report.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
                    color = SettingsStyle.Title,
                )
                Text(
                    "#${report.number} · ${stateLabel(report)}",
                    color = if (report.closed) SettingsStyle.SageSoft else SettingsStyle.Description,
                    fontSize = 13.sp,
                )
                Row(
                    Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SettingsStyle.ButtonFill)
                        .border(1.dp, SettingsStyle.ButtonBorder, RoundedCornerShape(20.dp))
                        .clickable(role = Role.Button) { open(report.url) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = SettingsStyle.SageSoft, modifier = Modifier.size(16.dp))
                    Text("Open on GitHub", color = SettingsStyle.Title, fontSize = 13.sp)
                }
                Text(
                    "Reply on GitHub. Orchard checks for news every few minutes and tells you when something happens.",
                    color = SettingsStyle.Caption,
                    fontSize = 12.sp,
                )
            }
        }
        if (state.events.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (state.activeLoading) CircularProgressIndicator(color = SettingsStyle.Sage, modifier = Modifier.size(28.dp))
                    else Text(
                        "No activity yet. Replies, commits that mention this report, and its closing show up here.",
                        color = SettingsStyle.Description,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        items(state.events, key = { it.key }) { event -> EventRow(event, onClick = { open(event.url) }) }
    }
}

@Composable
private fun EventRow(event: SupportEvent, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF22302A)), contentAlignment = Alignment.Center) {
            Icon(eventIcon(event.kind), contentDescription = null, tint = SettingsStyle.SageSoft, modifier = Modifier.size(16.dp))
            if (event.kind == "comment" && event.actorAvatar.isNotBlank()) {
                AsyncImage(model = event.actorAvatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    event.title,
                    color = SettingsStyle.Title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (event.maintainer) {
                    Text(
                        "Maintainer",
                        color = SettingsStyle.SageSoft,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0x2244604F))
                            .border(1.dp, Color(0x556F9A80), CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                DateUtils.getRelativeTimeSpanString(event.createdAt * 1000).toString(),
                color = SettingsStyle.Caption,
                fontSize = 11.sp,
            )
            if (event.body.isNotBlank()) {
                Text(event.body, color = SettingsStyle.Description, fontSize = 13.sp, maxLines = 12, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
