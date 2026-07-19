package dev.sfg.orchard.connect

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

internal fun MainActivity.renderPlayer() {
    val track = snapshot.optJSONObject("track") ?: JSONObject()
    val playback = snapshot.optJSONObject("playback") ?: JSONObject()
    val queue = snapshot.optJSONArray("queue") ?: JSONArray()
    val current = secondsValue(playback.opt("currentTime"))
    val duration = secondsValue(playback.opt("duration"))

    content.addView(Ui.panel(this).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(playerArtwork(track, playback), LinearLayout.LayoutParams(match, dp(280)))

        addGap(16)
        addView(centerText(track.optString("title", "Nothing playing"), 20f, Ui.TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addGap(4)
        addView(centerText(track.optString("artist", serverUrl.ifEmpty { "Pair with Orchard on desktop." }), 14f, Ui.MUTED).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    })

    content.addView(Ui.panel(this).apply {
        addView(progressBar(current, duration), LinearLayout.LayoutParams(match, dp(32)))
        val times = Ui.row(this@renderPlayer)
        times.addView(Ui.text(this@renderPlayer, formatTime(current), 12f, Ui.MUTED), LinearLayout.LayoutParams(0, wrap, 1f))
        times.addView(Ui.text(this@renderPlayer, formatTime(duration), 12f, Ui.MUTED).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, wrap, 1f))
        addView(times)
        addGap(12)
        addView(buttonRow(
            Ui.button(this@renderPlayer, "Prev") { send("previous") }.apply { contentDescription = "Previous track" },
            Ui.button(this@renderPlayer, if (playback.optBoolean("isPlaying")) "Pause" else "Play", true) { send("play-pause") }.apply {
                contentDescription = if (playback.optBoolean("isPlaying")) "Pause playback" else "Start playback"
            },
            Ui.button(this@renderPlayer, "Next") { send("next") }.apply { contentDescription = "Next track" }
        ))
        addGap(8)
        addView(buttonRow(
            Ui.button(this@renderPlayer, "-15s") { send("seek", max(0.0, current - 15.0)) },
            Ui.button(this@renderPlayer, "+15s") { send("seek", min(duration.takeIf { it > 0 } ?: current + 15.0, current + 15.0)) },
            Ui.button(this@renderPlayer, "Vol -") { send("volume", max(0.0, playback.optDouble("volume", 0.0) - 0.06)) },
            Ui.button(this@renderPlayer, "Vol +") { send("volume", min(1.0, playback.optDouble("volume", 0.0) + 0.06)) }
        ))
    })

    content.addView(Ui.panel(this).apply {
        addView(tabs())
        addGap(12)
        when (activeTab) {
            "lyrics" -> renderLyrics(this)
            "search" -> renderSearch(this)
            "library" -> renderLibrary(this)
            "audio" -> renderAudio(this)
            else -> renderQueue(this, queue)
        }
    })
}

internal fun MainActivity.renderConnections() {
    content.addView(Ui.panel(this).apply {
        val row = Ui.row(this@renderConnections)
        val copy = LinearLayout(this@renderConnections).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(Ui.text(this@renderConnections, "Pairing", 18f, Ui.TEXT, true))
        copy.addView(Ui.text(this@renderConnections, status, 12f, Ui.MUTED))
        row.addView(copy, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(Ui.text(this@renderConnections, if (status == "Connected") "Online" else "Offline", 12f, Ui.TEXT, true))
        addView(row)
        addGap(12)
        val input = EditText(this@renderConnections).apply {
            setText(pairingInput)
            hint = "Pairing link, QR result, or desktop URL"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            setSingleLine(true)
            background = Ui.rounded(this@renderConnections, Ui.RAISED, Ui.BORDER, radiusDp = 6)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 14f
        }
        addView(input, LinearLayout.LayoutParams(match, dp(40)))
        addGap(12)
        addView(buttonRow(
            Ui.button(this@renderConnections, "Connect", true) {
                pairingInput = input.text.toString()
                connectToServer(pairingInput)
            },
            Ui.button(this@renderConnections, "Scan QR") { scanPairingQr() },
            Ui.button(this@renderConnections, "Reset") { resetPairing() }
        ))
    })

    content.addView(Ui.panel(this).apply {
        infoRow(this, "Desktop", serverUrl.ifEmpty { "Not paired" })
        infoRow(this, "Phone", deviceName, addDivider = true)
        infoRow(this, "Socket", if (socket != null) "Connected" else "Offline", addDivider = true)
    })

    content.addView(Ui.panel(this).apply {
        val available = updateInfo.status == "available" && updateInfo.update != null
        
        val header = Ui.row(this@renderConnections)
        header.addView(Ui.text(this@renderConnections, "Updates", 18f, Ui.TEXT, true), LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(Ui.text(this@renderConnections, "v${AppUpdate.installedVersionName(this@renderConnections)}", 13f, Ui.MUTED, true))
        addView(header)
        
        addGap(8)
        val summary = when {
            available -> "Version ${updateInfo.update?.optString("version")} is ready to install."
            updateInfo.status == "checking" -> "Checking for a newer build..."
            updateInfo.status == "error" -> "Could not reach the update service."
            else -> "This is the newest available build."
        }
        addView(Ui.text(this@renderConnections, summary, 14f, Ui.TEXT))
        addGap(12)
        addView(Ui.button(this@renderConnections, if (available) "Download Update" else "Check for Updates", available) {
            val update = updateInfo.update
            if (available && update != null) AppUpdate.openDownload(this@renderConnections, update.optString("apkUrl")) else checkForUpdates()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(match, dp(40))
        })
    })
}

private fun MainActivity.renderQueue(parent: LinearLayout, queue: JSONArray) {
    if (queue.length() == 0) {
        parent.addView(Ui.text(this, "Queue is empty.", 12f, Ui.MUTED))
        return
    }
    for (index in 0 until min(queue.length(), 20)) {
        val item = queue.optJSONObject(index) ?: continue
        parent.addView(itemRow(item, 
            Ui.smallButton(this, "Play") { send("play-queue-index", index) }, 
            Ui.smallButton(this, "Remove") { send("remove-queue-index", index) }
        ))
    }
}

private fun MainActivity.renderLyrics(parent: LinearLayout) {
    val lyrics = snapshot.optJSONObject("lyrics") ?: JSONObject()
    val lines = lyrics.optJSONArray("lines") ?: JSONArray()
    val active = if (lyrics.optString("mode") == "synced") activeLyric(lines, secondsValue(snapshot.optJSONObject("playback")?.opt("currentTime"))) else -1
    if (lines.length() == 0) {
        parent.addView(Ui.text(this, "No lyrics.", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        return
    }
    val start = if (active >= 0) max(0, active - 3) else 0
    val end = if (active >= 0) min(lines.length(), active + 7) else min(lines.length(), 10)
    for (index in start until end) {
        val line = lines.optJSONObject(index) ?: continue
        val isActive = index == active
        parent.addView(TextView(this).apply {
            val lineText = lyricLineText(line)
            text = if (isActive && lyrics.optString("mode") == "synced") {
                wordSyncedLyricText(line, lineText, secondsValue(snapshot.optJSONObject("playback")?.opt("currentTime")))
            } else {
                lineText
            }
            textSize = if (isActive) 18f else 15f
            setTextColor(if (isActive) Ui.ACCENT else Ui.MUTED)
            typeface = Typeface.create(if (isActive) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        })
    }
}

private fun lyricLineText(line: JSONObject): String {
    val text = line.optString("text").trim()
    if (text.isNotEmpty()) return text

    val words = line.optJSONArray("words") ?: JSONArray()
    return (0 until words.length())
        .mapNotNull { words.optJSONObject(it)?.optString("text")?.takeIf { word -> word.isNotBlank() } }
        .joinToString(" ")
        .trim()
}

private fun wordSyncedLyricText(line: JSONObject, fallbackText: String, currentTime: Double): CharSequence {
    val words = line.optJSONArray("words") ?: return fallbackText
    if (words.length() == 0) return fallbackText

    val pieces = mutableListOf<Pair<String, Boolean>>()
    for (index in 0 until words.length()) {
        val word = words.optJSONObject(index) ?: continue
        val text = word.optString("text").trim()
        if (text.isEmpty()) continue
        val start = secondsValue(word.opt("startTime"))
        val explicitEnd = secondsValue(word.opt("endTime"))
        val nextStart = words.optJSONObject(index + 1)?.let { secondsValue(it.opt("startTime")) } ?: 0.0
        val end = when {
            explicitEnd > start -> explicitEnd
            nextStart > start -> nextStart
            else -> start + 0.4
        }
        pieces.add(text to (currentTime >= start && currentTime < end))
    }
    if (pieces.isEmpty()) return fallbackText

    val value = pieces.joinToString(" ") { it.first }
    val styled = SpannableString(value)
    var cursor = 0
    pieces.forEach { (word, active) ->
        val start = value.indexOf(word, cursor)
        if (start >= 0) {
            val end = start + word.length
            if (active) styled.setSpan(ForegroundColorSpan(Ui.TEXT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = end
        }
    }
    return styled
}

private fun MainActivity.renderSearch(parent: LinearLayout) {
    val row = Ui.row(this)
    val input = EditText(this).apply {
        setText(searchText)
        hint = "Find songs"
        setSingleLine(true)
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.MUTED)
        background = Ui.rounded(this@renderSearch, Ui.RAISED, Ui.BORDER, radiusDp = 6)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        textSize = 14f
    }
    row.addView(input, LinearLayout.LayoutParams(0, dp(40), 1f))
    row.addView(Ui.button(this, "Search", primary = true) {
        searchText = input.text.toString()
        runSearch()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(wrap, dp(40)).margins(dp(8), 0, 0, 0)
    })
    parent.addView(row)
    parent.addGap(8)
    for (index in 0 until results.length()) {
        val item = results.optJSONObject(index) ?: continue
        parent.addView(itemRow(item, Ui.smallButton(this, "Play") { send("play-track", item.optJSONObject("playbackItem") ?: item) }))
    }
}

private fun MainActivity.tabs(): View {
    val row = Ui.row(this).apply {
        addView(tab("Queue", "queue"), LinearLayout.LayoutParams(dp(84), dp(38)).margins(0, 0, dp(8), 0))
        addView(tab("Library", "library"), LinearLayout.LayoutParams(dp(84), dp(38)).margins(0, 0, dp(8), 0))
        addView(tab("Audio", "audio"), LinearLayout.LayoutParams(dp(84), dp(38)).margins(0, 0, dp(8), 0))
        addView(tab("Lyrics", "lyrics"), LinearLayout.LayoutParams(dp(84), dp(38)).margins(0, 0, dp(8), 0))
        addView(tab("Search", "search"), LinearLayout.LayoutParams(dp(84), dp(38)))
    }
    return android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        clipToPadding = false
        addView(row)
    }
}

private fun MainActivity.tab(label: String, value: String): TextView {
    val active = activeTab == value
    return Ui.text(this, label, 13f, if (active) Ui.TEXT else Ui.MUTED, true).apply {
        gravity = Gravity.CENTER
        background = Ui.roundedRipple(
            this@tab,
            fill = if (active) 0xFF17241C.toInt() else Ui.SURFACE,
            stroke = if (active) Ui.ACCENT else Ui.BORDER,
            radiusDp = 6
        )
        isClickable = true
        isFocusable = true
        setOnClickListener {
            activeTab = value
            render()
        }
    }
}

internal fun MainActivity.renderLibrary(parent: LinearLayout) {
    val row = Ui.row(this)
    row.addView(Ui.button(this, "Refresh Library") {
        runLibrarySync()
    }.apply {
        layoutParams = LinearLayout.LayoutParams(match, dp(40))
    })
    parent.addView(row)
    parent.addGap(8)

    for (index in 0 until libraryResults.length()) {
        val item = libraryResults.optJSONObject(index) ?: continue
        parent.addView(itemRow(item, Ui.smallButton(this, "Play") { send("play-track", item) }))
    }
}

private fun MainActivity.runLibrarySync() {
    if (socket == null) return
    socket?.emit("connect:library", JSONObject().put("requestId", System.currentTimeMillis().toString()))
    status = "Loading library..."
    render()
}

private fun MainActivity.renderAudio(parent: LinearLayout) {
    val engine = snapshot.optJSONObject("audioEngine") ?: JSONObject()
    val config = engine.optJSONObject("config") ?: JSONObject()
    val presets = engine.optJSONArray("presets") ?: JSONArray()

    val eqEnabled = config.optBoolean("eqEnabled", false)
    val autoEqEnabled = config.optBoolean("autoEqEnabled", false)
    val activePreset = engine.optString("activePreset", "flat")

    parent.addView(Ui.text(this, "Audio Engine", 16f, Ui.TEXT, true))
    parent.addGap(8)

    parent.addView(buttonRow(
        Ui.button(this, if (eqEnabled) "Manual EQ: ON" else "Manual EQ: OFF", eqEnabled) {
            send("audio-engine-manual-eq", !eqEnabled)
        },
        Ui.button(this, if (autoEqEnabled) "Auto EQ: ON" else "Auto EQ: OFF", autoEqEnabled) {
            send("audio-engine-auto-eq", !autoEqEnabled)
        }
    ))

    parent.addGap(16)
    parent.addView(Ui.text(this, "Presets", 16f, Ui.TEXT, true))
    parent.addGap(8)

    for (i in 0 until presets.length()) {
        val preset = presets.optJSONObject(i) ?: continue
        val id = preset.optString("value")
        val label = preset.optString("label")
        val isActive = activePreset == id

        parent.addView(Ui.button(this, label, isActive) {
            send("audio-engine-preset", id)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(match, dp(40)).margins(0, 0, 0, dp(8))
        })
    }
}

private fun MainActivity.itemRow(item: JSONObject, vararg actions: View): LinearLayout {
    return Ui.row(this).apply {
        setPadding(0, dp(8), 0, dp(4))
        val copy = LinearLayout(this@itemRow).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(Ui.text(this@itemRow, item.optString("title", "Untitled"), 14f, Ui.TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        copy.addView(Ui.text(this@itemRow, item.optString("artist"), 11f, Ui.MUTED).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(copy, LinearLayout.LayoutParams(0, wrap, 1f))
        actions.forEach { addView(it, LinearLayout.LayoutParams(wrap, dp(28)).margins(dp(6), 0, 0, 0)) }
    }
}

private fun MainActivity.infoRow(parent: LinearLayout, label: String, value: String, addDivider: Boolean = false) {
    if (addDivider) {
        parent.addView(Ui.divider(this).apply {
            layoutParams = LinearLayout.LayoutParams(match, dp(1)).margins(0, dp(8), 0, dp(8))
        })
    }
    val row = Ui.row(this).apply {
        setPadding(0, dp(4), 0, dp(4))
    }
    row.addView(Ui.text(this, label, 13f, Ui.MUTED, true))
    row.addView(Ui.text(this, value, 13f, Ui.TEXT).apply {
        gravity = Gravity.END
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(0, wrap, 1f).margins(dp(12), 0, 0, 0))
    parent.addView(row)
}

private fun MainActivity.buttonRow(vararg views: View): LinearLayout {
    return Ui.row(this).apply {
        gravity = Gravity.CENTER
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, dp(40), 1f).margins(if (index == 0) 0 else dp(8), 0, 0, 0))
        }
    }
}

private fun MainActivity.centerText(value: String, sp: Float, color: Int, bold: Boolean = false): TextView {
    return Ui.text(this, value, sp, color, bold).apply { gravity = Gravity.CENTER }
}

private fun MainActivity.progressBar(current: Double, duration: Double): View {
    return SeekBar(this).apply {
        max = 1000
        progress = ((current / max(1.0, duration)) * 1000.0).toInt().coerceIn(0, 1000)
        progressDrawable.setTint(Ui.ACCENT)
        thumb?.setTint(Ui.ACCENT)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (duration <= 0.0) return
                val pct = (seekBar?.progress ?: 0) / 1000.0
                val targetSec = pct * duration
                send("seek", targetSec)
            }
        })
    }
}

private fun LinearLayout.addGap(size: Int) {
    addView(View(context), LinearLayout.LayoutParams(1, (size * context.resources.displayMetrics.density).toInt()))
}

private fun LinearLayout.LayoutParams.margins(left: Int, top: Int, right: Int, bottom: Int): LinearLayout.LayoutParams {
    setMargins(left, top, right, bottom)
    return this
}

private const val match = ViewGroup.LayoutParams.MATCH_PARENT
private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun MainActivity.renderSearchPage() {
    val searchContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    
    val searchBox = EditText(this).apply {
        setText(searchText)
        hint = "Albums, Songs, Lyrics, and More"
        setSingleLine(true)
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.MUTED)
        background = Ui.rounded(this@renderSearchPage, Ui.SURFACE, Ui.BORDER, radiusDp = 6)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        textSize = 15f
    }
    searchContainer.addView(searchBox, LinearLayout.LayoutParams(match, wrap))

    val spacer = View(this).apply {
        background = Ui.rounded(this@renderSearchPage, Ui.RAISED, android.graphics.Color.TRANSPARENT, radiusDp = 8)
    }
    searchContainer.addView(spacer, LinearLayout.LayoutParams(match, dp(48)).apply {
        topMargin = dp(24)
    })
    
    content.addView(searchContainer, LinearLayout.LayoutParams(match, wrap))
}
