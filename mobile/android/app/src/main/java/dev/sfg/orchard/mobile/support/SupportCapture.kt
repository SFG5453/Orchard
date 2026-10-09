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

package dev.sfg.orchard.mobile.support

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import dev.sfg.orchard.connect.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max

/** Largest upload the service accepts, with a little room for the form. */
internal const val MAX_SCREENSHOT_BYTES = 4800 * 1024
private const val MAX_EDGE = 3840

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * The whole window as the user sees it. PixelCopy reads the composited surface,
 * so blurred glass and video frames come out right where View.draw would not.
 */
internal suspend fun captureWindow(activity: Activity): Bitmap? = suspendCancellableCoroutine { done ->
    val view = activity.window.decorView
    if (view.width <= 0 || view.height <= 0) {
        done.resume(null)
        return@suspendCancellableCoroutine
    }
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    PixelCopy.request(activity.window, bitmap, { result ->
        done.resume(bitmap.takeIf { result == PixelCopy.SUCCESS })
    }, Handler(Looper.getMainLooper()))
}

/** Decodes a picked image in software memory, scaled to fit [MAX_EDGE]. Re-encoding drops EXIF. */
internal fun decodePickedImage(context: Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val edge = max(info.size.width, info.size.height)
        if (edge > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / edge
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }
    }
}.getOrNull()

/** PNG when it fits, then shrinking JPEGs. Null when nothing fits the upload limit. */
internal fun encodeScreenshot(source: Bitmap): Pair<ByteArray, String>? {
    var image = source
    val edge = max(image.width, image.height)
    if (edge > MAX_EDGE) {
        val scale = MAX_EDGE.toFloat() / edge
        image = Bitmap.createScaledBitmap(image, (image.width * scale).toInt(), (image.height * scale).toInt(), true)
    }
    // Screens are mostly flat colour and text, where PNG is smaller and sharper.
    image.encode(Bitmap.CompressFormat.PNG, 100).takeIf { it.size <= MAX_SCREENSHOT_BYTES }?.let { return it to "image/png" }
    repeat(4) {
        val jpeg = image.encode(Bitmap.CompressFormat.JPEG, 88)
        if (jpeg.size <= MAX_SCREENSHOT_BYTES) return jpeg to "image/jpeg"
        image = Bitmap.createScaledBitmap(image, (image.width * 0.75f).toInt(), (image.height * 0.75f).toInt(), true)
    }
    return null
}

private fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int): ByteArray =
    ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

/** Public on GitHub: device and versions only, nothing about the listener. */
internal fun supportDiagnostics(context: Context, page: String): JSONObject {
    val metrics = context.resources.displayMetrics
    return JSONObject()
        .put("app", "Orchard Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        .put("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        .put("screen", "${metrics.widthPixels}x${metrics.heightPixels} @${metrics.density}x")
        .put("locale", Locale.getDefault().toLanguageTag())
        .apply { if (page.isNotBlank()) put("page", page) }
}
