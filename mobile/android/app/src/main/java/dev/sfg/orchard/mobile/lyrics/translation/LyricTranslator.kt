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

package dev.sfg.orchard.mobile.lyrics.translation

import android.content.Context
import android.os.Process
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.model.LyricTranslationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** What the lyrics view shows; [translations] is index-aligned with [lines] when they match. */
data class LyricTranslationState(
    val status: Status = Status.OFF,
    val message: String = "",
    val sourceName: String = "",
    val progress: Float = 0f,
    val lines: List<LyricLine>? = null,
    val translations: List<String?> = emptyList(),
) {
    enum class Status { OFF, IDLE, UNSUPPORTED, DOWNLOADING, TRANSLATING, READY, FAILED }

    /** Translations for [current], or null when they belong to another song. */
    fun translationsFor(current: List<LyricLine>): List<String?>? =
        translations.takeIf { lines === current || lines == current }
}

/**
 * Translates lyrics while a viewer is on screen. Local models remain the default; an API is opt-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricTranslator(
    context: Context,
    http: OkHttpClient,
    private val settings: StateFlow<OrchardSettings>,
    private val scope: CoroutineScope,
) {
    val packs = TranslationPacks(context.applicationContext, http)
    private val store = TranslationStore(context.applicationContext)
    private val apiKeys = TranslationApiKeys(context.applicationContext)
    private val remote = RemoteLyricTranslation(http)
    data class ApiKeyState(val saved: Set<LyricTranslationProvider>, val message: String = "") {
        fun has(provider: LyricTranslationProvider) = provider in saved
    }
    private val mutableApiKeys = MutableStateFlow(ApiKeyState(
        LyricTranslationProvider.entries.filterTo(mutableSetOf()) { it != LyricTranslationProvider.LOCAL && apiKeys.has(it) },
    ))
    val apiKeyState: StateFlow<ApiKeyState> = mutableApiKeys.asStateFlow()
    // One thread owns the model handle; lowest priority so a long chorus never stutters playback.
    private val modelThread = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            task.run()
        }, "Lyrics translation")
    }.asCoroutineDispatcher()
    private var handle = 0L
    private var loadedPack = ""
    private var unload: Job? = null

    private val generation = AtomicInteger()
    private val viewers = MutableStateFlow<Pair<List<LyricLine>, Int>?>(null)
    private val mutableState = MutableStateFlow(LyricTranslationState())
    val state: StateFlow<LyricTranslationState> = mutableState.asStateFlow()

    /** Installed packs, refreshed after downloads and removals, for the settings list. */
    private val mutableInstalled = MutableStateFlow<List<TranslationPack>>(emptyList())
    val installed: StateFlow<List<TranslationPack>> = mutableInstalled.asStateFlow()

    // Bumped to restart the current song after a failure or a removed model.
    private val retries = MutableStateFlow(0)
    // The pack the running song uses, and one removed under it: that song shows the removal
    // instead of fetching the model straight back. The next song, or Try again, downloads it.
    @Volatile private var activePack = ""
    @Volatile private var removed: Pair<String, List<LyricLine>?>? = null

    private data class Config(
        val enabled: Boolean, val quality: String, val provider: LyricTranslationProvider,
        val model: String, val endpoint: String,
    )

    init {
        scope.launch(Dispatchers.IO) { mutableInstalled.value = packs.installedPacks() }
        val wanted = settings.map {
            Config(it.translateLyrics && (it.lyricTranslationProvider != LyricTranslationProvider.LOCAL || TranslationNative.available),
                it.lyricTranslationQuality.key, it.lyricTranslationProvider,
                it.lyricTranslationModels[it.lyricTranslationProvider.key].orEmpty(), it.lyricTranslationEndpoint)
        }
            .distinctUntilChanged()
        scope.launch {
            combine(viewers, wanted, retries) { shown, config, _ -> shown?.first to config }
                // Before collectLatest joins the old run, so its native decode stops at the next token.
                .onEach { TranslationNative.takeIf { it.available }?.setGeneration(generation.incrementAndGet()) }
                .collectLatest { (lines, config) -> run(lines, config) }
        }
    }

    /** Called while a lyrics view shows [lines]; pair every call with [hide]. */
    fun show(lines: List<LyricLine>) = viewers.update { current ->
        lines to (if (current?.first === lines) current.second + 1 else 1)
    }

    fun hide(lines: List<LyricLine>) = viewers.update { current ->
        when {
            current == null || current.first !== lines -> current
            current.second > 1 -> lines to current.second - 1
            else -> null
        }
    }

    fun retry() {
        removed = null
        retries.update { it + 1 }
    }

    fun saveApiKey(provider: LyricTranslationProvider, raw: String) {
        if (provider == LyricTranslationProvider.LOCAL || raw.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val result = runCatching { apiKeys.save(provider, raw) }
            mutableApiKeys.update {
                it.copy(saved = if (result.isSuccess) it.saved + provider else it.saved,
                    message = result.exceptionOrNull()?.message.orEmpty())
            }
            if (result.isSuccess) retries.update { it + 1 }
        }
    }

    fun removeApiKey(provider: LyricTranslationProvider) {
        if (provider == LyricTranslationProvider.LOCAL) return
        scope.launch(Dispatchers.IO) {
            val result = runCatching { apiKeys.remove(provider) }
            mutableApiKeys.update {
                it.copy(saved = if (result.isSuccess) it.saved - provider else it.saved,
                    message = result.exceptionOrNull()?.message.orEmpty())
            }
            if (result.isSuccess) retries.update { it + 1 }
        }
    }

    fun remove(pack: TranslationPack) {
        if (activePack == pack.id) {
            removed = pack.id to state.value.lines
            retries.update { it + 1 }
        }
        scope.launch {
            withContext(modelThread) { if (loadedPack == pack.id) closeModel() }
            withContext(Dispatchers.IO) { packs.remove(pack.id) }
            mutableInstalled.value = withContext(Dispatchers.IO) { packs.installedPacks() }
        }
    }

    private suspend fun run(lines: List<LyricLine>?, config: Config) {
        if (!config.enabled) {
            mutableState.value = LyricTranslationState()
            return
        }
        // Nobody is looking; keep what is on screen for when they come back.
        if (lines == null) return
        activePack = ""
        if (config.provider != LyricTranslationProvider.LOCAL) {
            runRemote(lines, config)
            return
        }
        val texts = lines.map(::lineText)
        val plan = JSONObject(TranslationNative.plan(texts.map { it.encodeToByteArray() }.toTypedArray()).decodeToString())
        val source = plan.getString("source")
        val sourceName = plan.getString("name")
        val translate = plan.getJSONArray("translate")
        val base = LyricTranslationState(LyricTranslationState.Status.IDLE, sourceName = sourceName, lines = lines,
            translations = List(lines.size) { null })
        if (source.isEmpty()) {
            mutableState.value = base
            return
        }
        val pack = packs.resolve(source, config.quality)
        if (pack == null || pack.baseUrl.isEmpty()) {
            mutableState.value = base.copy(status = LyricTranslationState.Status.UNSUPPORTED,
                message = "$sourceName lyrics can't be translated yet.")
            return
        }
        activePack = pack.id
        if (removed?.let { it.first == pack.id && it.second === lines } == true) {
            mutableState.value = base.copy(status = LyricTranslationState.Status.FAILED,
                message = "The $sourceName model was removed.")
            return
        }

        val pending = LinkedHashMap<String, MutableList<Int>>()
        texts.forEachIndexed { index, text ->
            if (translate.getBoolean(index) && text.isNotEmpty()) pending.getOrPut(text) { mutableListOf() } += index
        }
        val results = arrayOfNulls<String>(lines.size)
        val cached = withContext(Dispatchers.IO) { store.lookup(pack.revision, pending.keys) }
        cached.forEach { (text, translation) -> pending.remove(text)?.forEach { results[it] = shown(text, translation) } }
        val ready = base.copy(status = LyricTranslationState.Status.READY, message = "Translated from $sourceName")
        if (pending.isEmpty()) {
            mutableState.value = ready.copy(translations = results.toList())
            return
        }

        try {
            if (!packs.installed(pack)) {
                mutableState.value = base.copy(status = LyricTranslationState.Status.DOWNLOADING,
                    message = "Downloading the $sourceName model", translations = results.toList())
                packs.download(pack) { fraction ->
                    // Whole percents only; a 20 MB download would otherwise recompose the lyrics thousands of times.
                    mutableState.update { if ((it.progress * 100).toInt() == (fraction * 100).toInt()) it else it.copy(progress = fraction) }
                }
                mutableInstalled.value = withContext(Dispatchers.IO) { packs.installedPacks() }
            }
            mutableState.value = base.copy(status = LyricTranslationState.Status.TRANSLATING,
                message = "Translating from $sourceName", translations = results.toList())
            val job = generation.get()
            withContext(modelThread) {
                unload?.cancel()
                openModel(pack)
                var published = System.nanoTime()
                for ((text, indices) in pending) {
                    ensureActive()
                    val out = TranslationNative.translate(handle, text.encodeToByteArray(), job)?.decodeToString() ?: continue
                    if (out.isEmpty()) continue
                    store.store(pack.revision, text, out)
                    indices.forEach { results[it] = shown(text, out) }
                    // Lines land every ~40 ms; repaint in batches like desktop.
                    if (System.nanoTime() - published > PUBLISH_NANOS) {
                        published = System.nanoTime()
                        mutableState.update { it.copy(translations = results.toList()) }
                    }
                }
                unload = scope.launch(modelThread) {
                    delay(UNLOAD_AFTER_MS)
                    closeModel()
                }
            }
            mutableState.value = ready.copy(translations = results.toList())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = base.copy(status = LyricTranslationState.Status.FAILED,
                message = error.message ?: "Translation failed", translations = results.toList())
        }
    }

    private suspend fun runRemote(lines: List<LyricLine>, config: Config) {
        val texts = lines.map(::lineText)
        val base = LyricTranslationState(LyricTranslationState.Status.IDLE, lines = lines,
            translations = List(lines.size) { null })
        val pending = LinkedHashMap<String, MutableList<Int>>()
        texts.forEachIndexed { index, text ->
            if (text.isNotEmpty()) pending.getOrPut(text) { mutableListOf() } += index
        }
        val results = arrayOfNulls<String>(lines.size)
        try {
            if (config.model.isBlank()) error("Enter a translation model in Settings.")
            val key = withContext(Dispatchers.IO) { apiKeys.load(config.provider) }
            if (config.provider != LyricTranslationProvider.CUSTOM && key.isBlank()) error("Add an API key in Settings.")
            RemoteLyricTranslation.endpoint(config.provider, config.endpoint, key)
            val revision = RemoteLyricTranslation.cacheRevision(config.provider, config.model, config.endpoint)
            val cached = withContext(Dispatchers.IO) { store.lookup(revision, pending.keys) }
            cached.forEach { (text, translation) -> pending.remove(text)?.forEach { results[it] = shown(text, translation) } }
            val ready = base.copy(status = LyricTranslationState.Status.READY,
                message = "Translated with ${config.provider.label}")
            if (pending.isEmpty()) {
                mutableState.value = ready.copy(translations = results.toList())
                return
            }
            mutableState.value = base.copy(status = LyricTranslationState.Status.TRANSLATING,
                message = "Translating with ${config.provider.label}", translations = results.toList())
            // Twenty-four lines fit comfortably in a response without losing chorus alignment.
            pending.keys.toList().chunked(RemoteLyricTranslation.BATCH_SIZE).forEach { batch ->
                val output = remote.translate(config.provider, config.model, config.endpoint, key, batch)
                withContext(Dispatchers.IO) {
                    batch.zip(output).forEach { (text, translated) -> store.store(revision, text, translated) }
                }
                batch.zip(output).forEach { (text, translated) ->
                    pending.remove(text)?.forEach { results[it] = shown(text, translated) }
                }
                mutableState.value = base.copy(status = LyricTranslationState.Status.TRANSLATING,
                    message = "Translating with ${config.provider.label}", translations = results.toList())
            }
            mutableState.value = ready.copy(translations = results.toList())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = base.copy(status = LyricTranslationState.Status.FAILED,
                message = error.message ?: "Translation failed", translations = results.toList())
        }
    }

    // Model thread only.
    private fun openModel(pack: TranslationPack) {
        if (handle != 0L && loadedPack == pack.id) return
        closeModel()
        handle = TranslationNative.open("libLiteRt.so", packs.directory(pack.id).path.encodeToByteArray(), THREADS)
        loadedPack = pack.id
    }

    // Model thread only.
    private fun closeModel() {
        if (handle != 0L) TranslationNative.close(handle)
        handle = 0L
        loadedPack = ""
    }

    private companion object {
        // Two threads keep a song to a few seconds without heating the phone.
        const val THREADS = 2
        // About 75 MB resident while loaded; songs in a row reuse it, idle gives it back.
        const val UNLOAD_AFTER_MS = 90_000L
        const val PUBLISH_NANOS = 120_000_000L

        fun lineText(line: LyricLine): String =
            line.text.trim().ifEmpty { line.words.joinToString(" ") { it.text.trim() }.trim() }

        // "Oh oh oh" comes back as "Oh oh oh"; showing it twice is noise.
        fun shown(text: String, translation: String): String? =
            translation.takeUnless { it.trim().equals(text.trim(), ignoreCase = true) }
    }
}
