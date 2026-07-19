package dev.sfg.orchard.connect

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject

internal fun MainActivity.playerArtwork(track: JSONObject, playback: JSONObject): View {
    val placeholder = TextView(this).apply {
        text = "Orchard"
        textSize = 24f
        setTextColor(Ui.MUTED)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }

    val art = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Album artwork"
    }

    val artUrl = track.optString("artwork")
    if (artUrl.isBlank()) {
        currentArtwork = null
        currentArtworkUrl = ""
        art.visibility = View.GONE
        placeholder.visibility = View.VISIBLE
        updateMediaSession(track, playback)
    } else {
        val matchingArtwork = currentArtwork.takeIf { currentArtworkUrl == artUrl }
        if (matchingArtwork != null) art.setImageBitmap(matchingArtwork)

        art.visibility = View.VISIBLE
        placeholder.visibility = if (matchingArtwork == null) View.VISIBLE else View.GONE
        imageLoader.load(artUrl, art) { bitmap ->
            if (bitmap != null) {
                currentArtwork = bitmap
                currentArtworkUrl = artUrl
                art.visibility = View.VISIBLE
                placeholder.visibility = View.GONE
            } else if (matchingArtwork == null) {
                art.visibility = View.GONE
                placeholder.visibility = View.VISIBLE
            }
            updateMediaSession(track, playback)
        }
    }

    return FrameLayout(this).apply {
        background = Ui.rounded(this@playerArtwork, Ui.SURFACE, Ui.BORDER, radiusDp = 8)
        clipToOutline = true
        addView(placeholder, FrameLayout.LayoutParams(matchParent, matchParent))
        addView(art, FrameLayout.LayoutParams(matchParent, matchParent))
    }
}

private const val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
