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

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.BestMixJob
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.LocalBestMixJob
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Playlists credit the most frequent artist when the owner is YouTube Music itself. */
internal fun collectionArtistName(detail: BrowseDetail): String {
    return if (detail.kind == CatalogKind.PLAYLIST) {
        detail.artist.takeIf { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) }
            ?: detail.tracks.map { it.artist }.filter { it.isNotBlank() && !it.equals("Unknown artist", true) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: detail.artist.ifBlank { "YouTube Music" }
    } else {
        detail.artist.ifBlank {
            detail.tracks.firstOrNull { it.artist.isNotBlank() }?.artist.orEmpty()
        }
    }
}

/** Release type for the hero info line: "EP", "Single", "Album" or "Playlist". */
internal fun collectionKindLabel(detail: BrowseDetail): String {
    if (detail.kind == CatalogKind.PLAYLIST) return "Playlist"
    val clean = detail.subtitle.trim()
    val visibilityOnly = clean.equals("unlisted", true) || clean.equals("public", true) || clean.equals("private", true)
    return when {
        clean.isNotBlank() && !visibilityOnly -> clean
        detail.kind == CatalogKind.ALBUM -> "Album"
        else -> "Playlist"
    }
}

/** Best Mix sort state shared by the hero button and the chrome's overflow menu. */
internal class BestMixLauncher(
    private val start: (BestMixLauncher) -> Unit,
    val undownloadedCount: Int,
    val estimatedMb: Int,
    private val needsPrompt: Boolean,
    private val job: State<BestMixJob?>,
    private val key: String,
) {
    // Derived from the launcher-owned run, so leaving and re-entering the screen keeps the state.
    val isSorting: Boolean get() = job.value?.key == key
    val statusText: String get() = if (isSorting) job.value?.status.orEmpty() else ""
    var showPrompt by mutableStateOf(false)

    fun trigger() {
        if (job.value != null) return
        if (needsPrompt) showPrompt = true else run()
    }

    fun run() {
        if (job.value != null) return
        start(this)
    }
}

@Composable
internal fun rememberBestMixLauncher(
    detail: BrowseDetail,
    downloadedTrackIds: Set<String>,
    onPlayAll: (List<Track>, String) -> Unit,
    onPlayBestMix: ((List<Track>, String, (String) -> Unit, () -> Unit) -> Unit)?,
): BestMixLauncher {
    val undownloaded = remember(detail.tracks, downloadedTrackIds) { detail.tracks.filter { it.id !in downloadedTrackIds } }
    // Unknown durations count as 3.5 minutes; 20 KB/s approximates the cached stream bitrate.
    val estimatedMb = remember(undownloaded) {
        val ms = undownloaded.sumOf { if (it.durationMs > 0) it.durationMs else 210_000L }
        (ms / 1000.0 * 20.0 / 1024.0).roundToInt().coerceAtLeast(1)
    }
    val playAll by rememberUpdatedState(onPlayAll)
    val playBestMix by rememberUpdatedState(onPlayBestMix)
    val job = rememberUpdatedState(LocalBestMixJob.current)
    return remember(detail, undownloaded.size, estimatedMb) {
        BestMixLauncher(
            start = { launcher ->
                val external = playBestMix
                if (external != null) {
                    external(detail.tracks, detail.title, {}, {})
                } else {
                    // No Best Mix host here: play in order rather than sort on half the evidence.
                    playAll(detail.tracks, detail.title)
                }
            },
            undownloadedCount = undownloaded.size,
            estimatedMb = estimatedMb,
            needsPrompt = undownloaded.isNotEmpty(),
            job = job,
            key = BestMixJob.collectionKey(detail.title),
        )
    }
}

/** Shares through the app handler when present, else a plain text intent. */
internal fun shareCollection(context: Context, detail: BrowseDetail, onShare: ((BrowseDetail) -> Unit)?) {
    if (onShare != null) {
        onShare(detail)
        return
    }
    val artistName = collectionArtistName(detail)
    val target = if (artistName.isNotBlank()) "${detail.title} by $artistName" else detail.title
    val intent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, "Listen to $target on Orchard")
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(intent, "Share ${detail.title}"))
}

@Composable
internal fun BestMixButton(isSorting: Boolean, statusText: String, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "BestMixGlow")
    val borderGlow by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "BestMixBorderGlow",
    )
    val sparkleScale by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "BestMixSparkleScale",
    )

    Button(
        onClick = onClick,
        enabled = !isSorting,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.16f),
            disabledContentColor = Color.White,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(42.dp)
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        LocalAccent.current.copy(alpha = borderGlow * 0.8f),
                        Color.White.copy(alpha = 0.40f),
                        LocalAccent.current.copy(alpha = borderGlow),
                    ),
                ),
                shape = RoundedCornerShape(20.dp),
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (isSorting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = LocalAccent.current,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = "Best mix",
                    tint = LocalAccent.current,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer {
                            scaleX = sparkleScale
                            scaleY = sparkleScale
                        },
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isSorting) statusText.ifBlank { "Sorting Best Mix..." } else "Best mix",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color.White,
            )
        }
    }
}

@Composable
internal fun BestMixDownloadPrompt(
    trackCount: Int,
    estimatedMb: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = LocalAccent.current,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Best Mix Offline Analysis", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    "Best Mix analyzes harmonic keys, tempo, and cue points locally to arrange your music seamlessly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Downloading $trackCount song${if (trackCount == 1) "" else "s"} could take up to ~$estimatedMb MB of storage.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = LocalAccent.current,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current),
            ) {
                Text("Download & Sort", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.7f))
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = CanopyColors.Surface,
    )
}
