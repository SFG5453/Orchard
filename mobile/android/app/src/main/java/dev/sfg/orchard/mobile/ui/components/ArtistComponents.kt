/*
 * Copyright (C) 2026 SFG545
 * Copyright (C) 2026 Convx Project contributors
 *
 * This file is part of Orchard.
 *
 * Layout adapted from the artist header and sections in ArtistScreen
 * (app/src/main/kotlin/com/convx/music/ui/screens/artist/ArtistScreen.kt) in
 * Convx v1.5.2, https://github.com/cosmictaserdev-creator/Convx, licensed under the
 * GNU General Public License version 3. It is combined with Orchard under section 13
 * of the GNU GPL v3 and GNU AGPL v3.
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

package dev.sfg.orchard.mobile.ui.components

import dev.sfg.orchard.mobile.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.motion.popOnChange
import dev.sfg.orchard.mobile.ui.motion.pressScale
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Artist hero: faded portrait, centred name (Apple's logo when it has one), then Play, shuffle and follow. */
@Composable
fun ArtistHero(
    detail: BrowseDetail,
    palette: ArtworkPalette,
    onPlayAll: (List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    shuffleAvailable: Boolean,
    onSave: (BrowseDetail) -> Unit,
    isSaved: Boolean,
) {
    DetailHeroFrame(artworkUrl = detail.artworkUrl, description = detail.title) {
        ArtistName(
            detail = detail,
            logoHeight = 72.dp,
            logoMaxWidth = 260.dp,
            textStyle = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 44.sp,
                lineHeight = 48.sp,
                letterSpacing = (-1).sp,
            ),
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        Spacer(Modifier.height(20.dp))
        ArtistPlayRow(
            onPlay = { onPlayAll(detail.tracks, detail.title) },
            playEnabled = detail.tracks.isNotEmpty(),
            playTint = palette.deep,
            onShuffle = { onShuffle(detail.tracks, detail.title) },
            shuffleEnabled = detail.tracks.isNotEmpty() && shuffleAvailable,
            onFollow = { onSave(detail) },
            isSaved = isSaved,
        )
    }
}

/** The artist's name: Apple's logo artwork when it has one and it loads, centred text otherwise. */
@Composable
fun ArtistName(
    detail: BrowseDetail,
    logoHeight: Dp,
    textStyle: TextStyle,
    logoMaxWidth: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    logoAlignment: Alignment = Alignment.Center,
) {
    val name = detail.title.trim()
    var logoFailed by remember(detail.logoUrl) { mutableStateOf(false) }
    if (detail.logoUrl.isNotBlank() && !logoFailed) {
        AsyncImage(
            model = detail.logoUrl,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            alignment = logoAlignment,
            onError = { logoFailed = true },
            // Both caps bind: blocky logos stop at the height, wide ones at the width.
            modifier = modifier.widthIn(max = logoMaxWidth).fillMaxWidth().height(logoHeight),
        )
    } else {
        Text(
            text = tr(name),
            style = textStyle,
            color = Color.White,
            textAlign = textAlign,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

/** Shuffle, a wide Play pill, then follow. */
@Composable
private fun ArtistPlayRow(
    onPlay: () -> Unit,
    playEnabled: Boolean,
    playTint: Color,
    onShuffle: () -> Unit,
    shuffleEnabled: Boolean,
    onFollow: () -> Unit,
    isSaved: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistRoundButton(
            onClick = onShuffle,
            icon = Icons.Rounded.Shuffle,
            contentDescription = tr("Shuffle"),
            enabled = shuffleEnabled,
            modifier = Modifier.riseIn(2, fromScale = 0.6f),
        )
        val source = remember { MutableInteractionSource() }
        Surface(
            onClick = onPlay,
            enabled = playEnabled,
            shape = CircleShape,
            color = if (playEnabled) Color.White else Color.White.copy(alpha = 0.3f),
            interactionSource = source,
            modifier = Modifier.weight(1f).height(52.dp).riseIn(3, fromScale = 0.8f).pressScale(source, 0.94f),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = playTint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = tr("Play"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = playTint,
                )
            }
        }
        ArtistRoundButton(
            onClick = onFollow,
            icon = if (isSaved) Icons.Filled.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (isSaved) "Unfollow artist" else "Follow artist",
            tint = CanopyColors.Favorite,
            modifier = Modifier.riseIn(4, fromScale = 0.6f),
        )
    }
}

/** Flat translucent circle beside the Play pill; glass would read heavier than the pill. */
@Composable
private fun ArtistRoundButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = CanopyColors.Text,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        interactionSource = source,
        modifier = modifier.pressScale(source, 0.88f).size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                modifier = Modifier.size(22.dp).popOnChange(icon, peak = 1.45f),
            )
        }
    }
}

@Composable
fun DetailBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier.padding(start = 8.dp, top = 8.dp)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back"), tint = CanopyColors.Text)
    }
}
