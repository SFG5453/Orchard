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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.ui.motion.bounceClickable
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.OrchardMark
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/** Compact masthead: wordmark and actions on one row, greeting below. */
@Composable
internal fun GlassHomeHeader(
    auth: AuthState,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
) {
    val firstName = remember(auth) { firstNameOf(auth) }
    val avatarUrl = (auth as? AuthState.SignedIn)?.avatarUrl.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).riseIn(distance = 12f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The mark twirls in when Home opens. It is a tree; it has earned a little spin.
            val spin = remember { Animatable(-120f) }
            LaunchedEffect(Unit) { spin.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 120f)) }
            OrchardMark(Modifier.size(26.dp).graphicsLayer { rotationZ = spin.value })
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Orchard",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 23.sp,
                    letterSpacing = (-0.6).sp,
                ),
                color = CanopyColors.Text,
                modifier = Modifier.weight(1f),
            )
            HeaderAction(onClick = onSearch, description = "Search") {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = CanopyColors.Text,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            HeaderAction(onClick = onProfile, description = "Profile") {
                ProfileAvatar(avatarUrl, firstName, Modifier.size(34.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        // Large title scrolls away with the feed; that is what gives the first screen its weight.
        Text(
            text = "Listen now",
            fontSize = 34.sp,
            lineHeight = 41.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.02).em,
            color = CanopyColors.Text,
            maxLines = 1,
            modifier = Modifier.riseIn(1, distance = 22f),
        )
    }
}

/** 48dp touch target around a smaller glyph. */
@Composable
private fun HeaderAction(
    onClick: () -> Unit,
    description: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .bounceClickable(CircleShape, 0.85f, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
internal fun ProfileAvatar(url: String, name: String, modifier: Modifier = Modifier) {
    if (url.isNotBlank()) {
        ArtworkTile(url = url, description = name, modifier = modifier, radius = 999)
    } else {
        Box(
            modifier = modifier.clip(CircleShape).background(CanopyColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Person,
                contentDescription = name,
                tint = LocalAccent.current,
                modifier = Modifier.fillMaxSize(0.6f),
            )
        }
    }
}

private fun firstNameOf(auth: AuthState): String =
    (auth as? AuthState.SignedIn)?.displayName?.trim()?.split(" ")?.firstOrNull()?.ifBlank { null }
        ?: "Listener"

/** Rail header led by the account avatar, e.g. "Your playlists / Khyren Joseph". */
@Composable
internal fun AccountSectionTitle(
    label: String,
    auth: AuthState,
    onClick: () -> Unit,
) {
    val name = remember(auth) {
        (auth as? AuthState.SignedIn)?.displayName?.trim()?.ifBlank { null } ?: "Your library"
    }
    val avatarUrl = (auth as? AuthState.SignedIn)?.avatarUrl.orEmpty()
    HomeSectionTitle(
        title = name,
        label = label,
        thumbnail = { ProfileAvatar(avatarUrl, name, Modifier.size(48.dp)) },
        onClick = onClick,
    )
}

/** Rail title; [subtitle] sits above it as an eyebrow. */
@Composable
internal fun HomeSectionHeader(
    title: String,
    subtitle: String? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    HomeSectionTitle(title = title, label = subtitle?.ifBlank { null }, onClick = onSeeAll)
}
