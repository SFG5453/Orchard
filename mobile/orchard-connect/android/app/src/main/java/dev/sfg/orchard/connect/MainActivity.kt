package dev.sfg.orchard.connect

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    internal val imageLoader = ImageLoader()
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    internal lateinit var navContainer: LinearLayout
    internal lateinit var content: LinearLayout
    internal var socket: OrchardSocket? = null
    internal var status = "Disconnected"
    internal var serverUrl = ""
    internal var pairingInput = ""
    internal var searchText = ""
    private var activeScreen = "search"
    internal var activeTab = "queue"
    internal var snapshot = JSONObject()
    internal var results = JSONArray()
    internal var libraryResults = JSONArray()
    internal var updateInfo = UpdateInfo()
    internal val deviceName by lazy { deviceDisplayName() }
    internal var mediaSession: MediaSession? = null
    internal var currentArtwork: android.graphics.Bitmap? = null
    internal var currentArtworkUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("orchard-connect", MODE_PRIVATE)
        serverUrl = prefs.getString(SERVER_URL_KEY, "").orEmpty()
        pairingInput = serverUrl
        setupMediaSession()
        buildFrame()
        checkForUpdates()
        openInitialPairingUrl(intent?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { connectToServer(it.toString()) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PAIRING_SCAN_REQUEST || resultCode != RESULT_OK) return
        val scanned = data?.getStringExtra("SCAN_RESULT").orEmpty().trim()
        if (scanned.isEmpty()) {
            status = "No QR code found"
            render()
            return
        }
        activeScreen = "connections"
        pairingInput = scanned
        connectToServer(scanned)
    }

    override fun onDestroy() {
        socket?.disconnect()
        executor.shutdownNow()
        mediaSession?.release()
        super.onDestroy()
    }

    private fun buildFrame() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        setContentView(root)
        
        content = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL 
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(ScrollView(this).apply { 
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content) 
        }, LinearLayout.LayoutParams(match, 0, 1f))
        
        root.addView(Ui.divider(this))
        navContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(16))
            setBackgroundColor(Ui.SURFACE)
        }
        root.addView(navContainer)
        
        render()
    }

    private fun navTab(label: String, icon: String, screen: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                activeScreen = screen
                render()
            }
            
            val isActive = activeScreen == screen
            val iconView = TextView(this@MainActivity).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(if (isActive) 0xFFFFFFFF.toInt() else Ui.MUTED)
                if (isActive) {
                    background = Ui.rounded(0xFF6B7A95.toInt(), radius = 16)
                    setPadding(dp(20), dp(4), dp(20), dp(4))
                } else {
                    setPadding(dp(20), dp(4), dp(20), dp(4))
                }
            }
            addView(iconView, LinearLayout.LayoutParams(wrap, wrap))
            
            val labelView = TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (isActive) 0xFFFFFFFF.toInt() else Ui.MUTED)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            addView(labelView, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(4) })
        }
    }

    internal fun render() {
        navContainer.removeAllViews()
        navContainer.addView(navTab("Home", "🏠", "home"), LinearLayout.LayoutParams(0, wrap, 1f))
        navContainer.addView(navTab("Search", "🔍", "search"), LinearLayout.LayoutParams(0, wrap, 1f))
        navContainer.addView(navTab("Library", "🎵", "library"), LinearLayout.LayoutParams(0, wrap, 1f))

        content.removeAllViews()
        when (activeScreen) {
            "search" -> renderSearchPage()
            "library" -> renderLibrary(content)
            else -> {
                renderPlayer()
                renderConnections()
            }
        }
    }

    private fun openInitialPairingUrl(initialUrl: Uri?) {
        executor.execute {
            val saved = prefs.getString(SERVER_URL_KEY, "").orEmpty()
            val host = prefs.getString(SERVER_HOST_KEY, "").orEmpty()
            val discovered = ConnectDiscovery.discoverServerUrl(saved, host)
            runOnUiThread {
                if (discovered.isNotEmpty()) {
                    serverUrl = discovered
                    pairingInput = discovered
                }
                if (initialUrl != null) connectToServer(initialUrl.toString()) else if (discovered.isNotEmpty()) connectToServer(discovered)
                render()
            }
        }
    }

    internal fun connectToServer(input: String = pairingInput) {
        val parsed = ConnectDiscovery.parsePairingInput(input)
        val rawServer = parsed.serverUrl.ifEmpty { ConnectDiscovery.cleanServerUrl(input).ifEmpty { serverUrl } }
        executor.execute {
            val target = if (parsed.token.isNotEmpty()) rawServer else ConnectDiscovery.discoverServerUrl(rawServer, ConnectDiscovery.serverHost(serverUrl))
            runOnUiThread {
                if (target.isEmpty()) {
                    status = "Enter the desktop pairing link."
                    render()
                    return@runOnUiThread
                }
                socket?.disconnect()
                status = "Connecting"
                serverUrl = target
                pairingInput = target
                prefs.edit().putString(SERVER_URL_KEY, target).putString(SERVER_HOST_KEY, ConnectDiscovery.serverHost(target)).apply()
                socket = OrchardSocket(target, socketListener(parsed.token)).also { it.connect() }
                render()
            }
        }
    }

    private fun socketListener(token: String): OrchardSocket.Listener {
        return object : OrchardSocket.Listener {
            override fun onConnected() {
                val deviceToken = prefs.getString(DEVICE_TOKEN_KEY, null) ?: ConnectDiscovery.createDeviceToken().also {
                    prefs.edit().putString(DEVICE_TOKEN_KEY, it).apply()
                }
                val hello = JSONObject().put("token", token).put("deviceToken", deviceToken).put("name", deviceName)
                socket?.emit("connect:hello", hello) { response ->
                    runOnUiThread { handleHelloResponse(response.optJSONObject("data") ?: response) }
                }
                runOnUiThread {
                    status = "Waiting for desktop approval"
                    render()
                }
            }

            override fun onDisconnected() = runOnUiThread {
                status = "Disconnected"
                socket = null
                render()
            }

            override fun onError(message: String) = runOnUiThread {
                status = "Desktop unreachable: $message"
                render()
            }

            override fun onEvent(name: String, payload: Any?) = runOnUiThread {
                when (name) {
                    "connect:approved" -> {
                        val data = payload as? JSONObject ?: JSONObject()
                        data.optString("deviceToken").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString(DEVICE_TOKEN_KEY, it).apply() }
                        status = "Connected"
                        snapshot = data.optJSONObject("state") ?: JSONObject()
                        updateMediaSession(snapshot.optJSONObject("track") ?: JSONObject(), snapshot.optJSONObject("playback") ?: JSONObject())
                    }
                    "connect:state" -> {
                        snapshot = payload as? JSONObject ?: JSONObject()
                        updateMediaSession(snapshot.optJSONObject("track") ?: JSONObject(), snapshot.optJSONObject("playback") ?: JSONObject())
                    }
                    "connect:search-results" -> results = (payload as? JSONObject)?.optJSONArray("results") ?: JSONArray()
                    "connect:library-results" -> {
                        libraryResults = (payload as? JSONObject)?.optJSONArray("results") ?: JSONArray()
                        status = "Connected"
                    }
                    "connect:rejected" -> status = "Pairing rejected"
                    "connect:revoked" -> {
                        prefs.edit().remove(DEVICE_TOKEN_KEY).apply()
                        status = "Access revoked"
                    }
                }
                render()
            }
        }
    }

    private fun handleHelloResponse(payload: JSONObject) {
        when (payload.optString("status")) {
            "approved" -> {
                status = "Connected"
                snapshot = payload.optJSONObject("state") ?: JSONObject()
            }
            "expired" -> status = "Link expired"
            "pending" -> status = "Approve on desktop"
        }
        render()
    }

    internal fun send(type: String, value: Any? = JSONObject.NULL) {
        socket?.emit("connect:command", JSONObject().put("type", type).put("value", value ?: JSONObject.NULL))
    }

    internal fun runSearch() {
        val query = searchText.trim()
        if (query.isEmpty() || socket == null) return
        results = JSONArray()
        socket?.emit("connect:search", JSONObject().put("query", query).put("requestId", System.currentTimeMillis().toString()))
        render()
    }

    internal fun resetPairing() {
        prefs.edit().remove(DEVICE_TOKEN_KEY).remove(SERVER_HOST_KEY).remove(SERVER_URL_KEY).apply()
        socket?.disconnect()
        socket = null
        pairingInput = ""
        serverUrl = ""
        status = "Disconnected"
        render()
    }

    internal fun scanPairingQr() {
        val intent = Intent(this, PairingScanActivity::class.java).apply {
            action = "com.google.zxing.client.android.SCAN"
            putExtra("SCAN_FORMATS", "QR_CODE")
            putExtra("PROMPT_MESSAGE", "Scan Orchard Connect QR")
        }
        startActivityForResult(intent, PAIRING_SCAN_REQUEST)
    }

    internal fun checkForUpdates() {
        updateInfo = UpdateInfo("checking")
        render()
        executor.execute {
            val result = AppUpdate.check(this)
            runOnUiThread {
                updateInfo = result
                render()
            }
        }
    }

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun LinearLayout.addGap(size: Int) {
        addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(size)))
    }

    internal fun LinearLayout.LayoutParams.margins(left: Int, top: Int, right: Int, bottom: Int): LinearLayout.LayoutParams {
        setMargins(left, top, right, bottom)
        return this
    }
}

private const val match = ViewGroup.LayoutParams.MATCH_PARENT
private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
private const val PAIRING_SCAN_REQUEST = 4206
