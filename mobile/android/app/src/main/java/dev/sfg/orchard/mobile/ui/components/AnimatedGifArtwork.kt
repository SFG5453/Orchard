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

package dev.sfg.orchard.mobile.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** True for a local GIF cover, which ExoPlayer cannot play but the platform decoder can. */
internal fun isGifArtwork(url: String): Boolean = url.substringBefore('?').endsWith(".gif", ignoreCase = true)

/**
 * A GIF cover the user chose, looping silently. Decoded by the platform's own [ImageDecoder], so
 * no extra library is needed; if the file cannot be decoded the still artwork beneath stays visible.
 */
@Composable
internal fun AnimatedGifArtwork(url: String, active: Boolean, modifier: Modifier = Modifier) {
    val path = Uri.parse(url).path ?: return
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { view ->
            // update runs on every recomposition; decode once per file.
            if (view.tag != path) {
                view.tag = path
                view.setImageDrawable(runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) }.getOrNull())
            }
            (view.drawable as? AnimatedImageDrawable)?.let { drawable ->
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                if (active) drawable.start() else drawable.stop()
            }
        },
        onRelease = { view -> (view.drawable as? AnimatedImageDrawable)?.stop() },
    )
}
