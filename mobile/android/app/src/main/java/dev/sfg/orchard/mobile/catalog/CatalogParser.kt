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

package dev.sfg.orchard.mobile.catalog

import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.MUSIC_VIDEO_TYPE_ATV
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.model.Track
import org.json.JSONArray
import org.json.JSONObject

/** Tolerant normalizer for the renderer variants returned by YouTube Music. */
object CatalogParser {
    private val homeShelfKeys = setOf("musicCarouselShelfRenderer", "musicShelfRenderer", "gridRenderer")

    fun search(root: JSONObject): SearchResults {
        val items = allItems(root).distinctBy { "${it::class.simpleName}:${it.stableId}" }
        return SearchResults(
            tracks = items.filterIsInstance<CatalogItem.Song>().map { it.track },
            albums = items.filterIsInstance<CatalogItem.Record>().map { it.album },
            artists = items.filterIsInstance<CatalogItem.Performer>().map { it.artist },
            playlists = items.filterIsInstance<CatalogItem.Collection>().map { it.playlist },
        )
    }

    fun home(root: JSONObject): List<CatalogSection> {
        val shelves = homeShelves(root)
        val sections = shelves.mapIndexedNotNull { index, shelf ->
            val title = JsonTraversal.text(shelf.optJSONObject("title"))
                .ifBlank { JsonTraversal.text(shelf.optJSONObject("header")) }
                .ifBlank { JsonTraversal.renderers(shelf.optJSONObject("header"), "title").firstOrNull()?.let(JsonTraversal::text).orEmpty() }
                .ifBlank { "For you" }
            val contents = shelf.optJSONArray("contents") ?: shelf.optJSONArray("items") ?: JSONArray()
            val items = allItems(contents).distinctBy(CatalogItem::stableId)
            if (items.isEmpty()) null else CatalogSection("shelf-$index-$title", title, items)
        }
        if (sections.isNotEmpty()) return sections
        val fallback = allItems(root).distinctBy(CatalogItem::stableId)
        return if (fallback.isEmpty()) emptyList() else listOf(CatalogSection("listen-now", "Listen now", fallback))
    }

    /** Home mixes carousels, song shelves, and grids, including inside continuation actions. */
    private fun homeShelves(root: Any?): List<JSONObject> = buildList { collectHomeShelves(root, this) }

    private fun collectHomeShelves(value: Any?, output: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key in homeShelfKeys && child is JSONObject) output.add(child)
                    else collectHomeShelves(child, output)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) collectHomeShelves(value.opt(index), output)
        }
    }

    /** Continuation belonging to the home section list, not a nested album or playlist shelf. */
    fun homeContinuationToken(root: JSONObject): String {
        val sectionLists = JsonTraversal.renderers(root, "sectionListRenderer") +
            JsonTraversal.renderers(root, "sectionListContinuation")
        sectionLists.firstNotNullOfOrNull { section ->
            continuationIn(section.opt("continuations")).takeIf(String::isNotBlank)
                ?: section.optString("continuation").takeIf(String::isNotBlank)
        }?.let { return it }
        return continuationIn(root)
    }

    /**
     * Radio rows out of a `next` response. These ride a panel renderer of their own rather than the
     * shelf renderers everything else uses, and the byline is one string ("SZA • SOS • 3:02")
     * instead of separate columns, so it is split back apart for [track] to read.
     */
    fun upNext(root: JSONObject): List<Track> =
        JsonTraversal.renderers(root, "playlistPanelVideoRenderer").mapNotNull { renderer ->
            val endpoint = JsonTraversal.navigation(renderer)
            val videoId = renderer.optString("videoId").ifBlank { JsonTraversal.videoId(endpoint) }
            val title = JsonTraversal.text(renderer.optJSONObject("title"))
            if (videoId.isBlank() || title.isBlank()) return@mapNotNull null
            val byline = JsonTraversal.text(renderer.optJSONObject("longBylineText"))
                .ifBlank { JsonTraversal.text(renderer.optJSONObject("shortBylineText")) }
            val length = JsonTraversal.text(renderer.optJSONObject("lengthText"))
            val texts = buildList {
                add(title)
                byline.split(" • ", "•").map(String::trim).filterTo(this, String::isNotBlank)
                if (length.isNotBlank()) add(length)
            }
            track(
                videoId = videoId,
                title = title,
                texts = texts,
                art = JsonTraversal.largestThumbnail(renderer),
                renderer = renderer,
                musicVideoType = JsonTraversal.musicVideoType(endpoint),
            )
        }

    fun sectionItems(root: JSONObject, defaultArtist: String = ""): List<CatalogItem> {
        return allItems(root, defaultArtist).distinctBy(CatalogItem::stableId)
    }

    fun detail(id: String, root: JSONObject): BrowseDetail {
        val rawHeader = JsonTraversal.renderers(root, "musicResponsiveHeaderRenderer").firstOrNull()
            ?: JsonTraversal.renderers(root, "musicDetailHeaderRenderer").firstOrNull()
            ?: JsonTraversal.renderers(root, "musicImmersiveHeaderRenderer").firstOrNull()
            ?: JsonTraversal.renderers(root, "musicVisualHeaderRenderer").firstOrNull()
            ?: JsonTraversal.renderers(root, "musicEditablePlaylistDetailHeaderRenderer").firstOrNull()
        val header = rawHeader?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")?.optJSONObject("header")
            ?.optJSONObject("musicResponsiveHeaderRenderer")
            ?: rawHeader?.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
            ?: rawHeader?.optJSONObject("header")
            ?: rawHeader
        val metadata = JsonTraversal.renderers(root, "musicMetadataRenderer").firstOrNull()
        val microformat = JsonTraversal.renderers(root, "microformatDataRenderer").firstOrNull()
        val title = header.textField("title")
            .ifBlank { metadata.textField("title") }
            .ifBlank { microformat?.optString("title").orEmpty() }
            .ifBlank { "Collection" }
        val rawStrapline = listOf("straplineText", "straplineTextOne", "strapline", "author", "ownerText", "bylineText")
            .firstNotNullOfOrNull { field -> header.textField(field).takeIf(String::isNotBlank) }
            .orEmpty()
        val rawSubtitle = header.textField("subtitle")
        val rawSecondSubtitle = header.textField("secondSubtitle")
        val subtitle = rawSubtitle.ifBlank { rawStrapline }.ifBlank { rawSecondSubtitle }
            .ifBlank {
                header?.optJSONObject("subscriptionButton")?.let { subButton ->
                    val subRenderer = subButton.optJSONObject("subscribeButtonRenderer")
                    JsonTraversal.text(subRenderer?.optJSONObject("subscriberCountText"))
                        .ifBlank { JsonTraversal.text(subRenderer?.optJSONObject("longSubscriberCountText")) }
                        .ifBlank { JsonTraversal.text(subButton) }
                }.orEmpty()
            }
        val description = header.textField("description")
            .ifBlank { JsonTraversal.renderers(root, "musicDescriptionShelfRenderer").firstOrNull().textField("description") }

        val albumArtistName = extractHeaderArtist(header)
            .ifBlank { playlistAuthor(rawStrapline) }
            .ifBlank { playlistAuthor(subtitle) }

        val musicShelves = JsonTraversal.renderers(root, "musicShelfRenderer")
        val topSongShelf = musicShelves.firstOrNull { shelf ->
            val shelfTitle = JsonTraversal.text(shelf.optJSONObject("title"))
            shelfTitle.contains("Song", true) || shelfTitle.contains("Popular", true) || shelfTitle.contains("Top", true)
        } ?: musicShelves.firstOrNull()

        val kind = inferDetailKind(id)
        // A playlist's rows live in its own shelf.
        val trackShelf = JsonTraversal.renderers(root, "musicPlaylistShelfRenderer").firstOrNull()
            ?: topSongShelf
        val tracks = (trackShelf?.let { allItems(it, albumArtistName) } ?: allItems(root, albumArtistName))
            .filterIsInstance<CatalogItem.Song>()
            .map { it.track }
            .collapseDuplicates(kind)

        val carouselShelves = JsonTraversal.renderers(root, "musicCarouselShelfRenderer")
        val sections = carouselShelves.mapIndexedNotNull { index, shelf ->
            val rawHeader = shelf.optJSONObject("header")
            val headerNode = rawHeader?.optJSONObject("musicCarouselShelfBasicHeaderRenderer") ?: rawHeader
            val sectionTitle = JsonTraversal.text(headerNode)
                .ifBlank { JsonTraversal.renderers(rawHeader, "title").firstOrNull()?.let(JsonTraversal::text).orEmpty() }
                .ifBlank { "More" }

            val moreButton = headerNode?.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
                ?: headerNode?.optJSONObject("moreContentButton")?.optJSONObject("button")
                ?: shelf.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
            val endpointNode = moreButton?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?: moreButton?.optJSONObject("endpoint")?.optJSONObject("payload")
            val sectionBrowseId = endpointNode?.optString("browseId").orEmpty()
            val sectionParams = endpointNode?.optString("params").orEmpty()

            val contents = shelf.optJSONArray("contents") ?: JSONArray()
            val items = allItems(contents, albumArtistName).distinctBy(CatalogItem::stableId)
            if (items.isEmpty()) null else CatalogSection(
                id = "detail-section-$index-$sectionTitle",
                title = sectionTitle,
                items = items,
                browseId = sectionBrowseId,
                params = sectionParams,
            )
        }

        val related = (sections.flatMap { it.items } + allItems(root, albumArtistName))
            .filterNot { it is CatalogItem.Song }
            .distinctBy(CatalogItem::stableId)

        val artwork = JsonTraversal.largestThumbnail(header)
            .ifBlank { JsonTraversal.largestThumbnail(metadata) }
            .ifBlank { tracks.firstOrNull()?.artworkUrl.orEmpty() }

        val resolvedTracks = tracks.map { track -> track.withCollectionContext(kind, id, title, albumArtistName, artwork) }

        val year = listOf(rawSubtitle, rawSecondSubtitle, rawStrapline, subtitle)
            .flatMap { it.split(" • ", "·", "•", " ") }
            .firstOrNull { it.trim().matches(Regex("^\\d{4}$")) }
            ?.trim()
            .orEmpty()

        return BrowseDetail(
            id = id,
            kind = kind,
            title = title,
            subtitle = subtitle,
            description = description,
            artworkUrl = artwork,
            tracks = resolvedTracks,
            related = related,
            sections = sections,
            artist = albumArtistName,
            year = year,
        )
    }

    /**
     * Token for the next page of a track list. YouTube Music ships the first 100 rows inline and
     * hides the rest behind continuations, so a 900-song playlist arrives as ten browse calls.
     * Blank once the list is complete.
     */
    fun continuationToken(root: Any?): String {
        val shelf = JsonTraversal.renderers(root, "musicPlaylistShelfContinuation").firstOrNull()
            ?: JsonTraversal.renderers(root, "musicShelfContinuation").firstOrNull()
            ?: JsonTraversal.renderers(root, "musicPlaylistShelfRenderer").firstOrNull()
            ?: JsonTraversal.renderers(root, "musicShelfRenderer").firstOrNull()
        val shelfToken = shelf?.let(::continuationIn).orEmpty()
        if (shelfToken.isNotBlank()) return shelfToken
        // Newer pages append rows through onResponseReceivedActions instead of a shelf
        // continuation, and carry the next token in a trailing continuationItemRenderer. Only
        // scan the whole root for that shape; a detail page's carousels also hold tokens.
        val actionShape = (root as? JSONObject)?.has("onResponseReceivedActions") == true
        return if (shelf == null || actionShape) continuationIn(root) else ""
    }

    /** Tracks from a continuation page, carrying the collection context of the page they extend. */
    fun continuationTracks(detail: BrowseDetail, root: JSONObject): List<Track> =
        allItems(root, detail.artist)
            .filterIsInstance<CatalogItem.Song>()
            .map { it.track }
            .collapseDuplicates(detail.kind)
            .map { track ->
                track.withCollectionContext(detail.kind, detail.id, detail.title, detail.artist, detail.artworkUrl)
            }

    private fun continuationIn(shelf: Any?): String {
        if (shelf == null) return ""
        (shelf as? JSONObject)?.optString("continuation")?.takeIf(String::isNotBlank)?.let { return it }
        JsonTraversal.renderers(shelf, "nextContinuationData").firstOrNull()
            ?.optString("continuation")?.takeIf(String::isNotBlank)?.let { return it }
        JsonTraversal.renderers(shelf, "continuationCommand").firstOrNull()
            ?.optString("token")?.takeIf(String::isNotBlank)?.let { return it }
        return ""
    }

    private fun allItems(root: Any?, fallbackArtist: String = ""): List<CatalogItem> = buildList {
        JsonTraversal.renderers(root, "musicCardShelfRenderer").forEach { shelf ->
            cardShelf(shelf)?.let(::add)
            val cardTitle = JsonTraversal.text(shelf.optJSONObject("title"))
            val subtitle = JsonTraversal.text(shelf.optJSONObject("subtitle"))
            val isArtistCard = JsonTraversal.pageType(JsonTraversal.navigation(shelf)).contains("ARTIST", true) ||
                JsonTraversal.browseId(JsonTraversal.navigation(shelf)).startsWith("UC") ||
                subtitle.contains("Artist", true)
            val effectiveArtist = if (isArtistCard) cardTitle else fallbackArtist
            val contents = shelf.optJSONArray("contents") ?: JSONArray()
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer")
                    ?: contents.optJSONObject(i)
                if (item != null) responsive(item, effectiveArtist)?.let(::add)
            }
        }
        JsonTraversal.renderers(root, "musicResponsiveListItemRenderer").mapNotNullTo(this) { responsive(it, fallbackArtist) }
        JsonTraversal.renderers(root, "musicTwoRowItemRenderer").mapNotNullTo(this) { twoRow(it) }
    }

    private fun cardShelf(shelf: JSONObject): CatalogItem? {
        val titleRuns = JsonTraversal.runs(shelf.optJSONObject("title"))
        val title = JsonTraversal.text(shelf.optJSONObject("title"))
        if (title.isBlank()) return null
        val subtitle = JsonTraversal.text(shelf.optJSONObject("subtitle"))
        val endpoint = cardEndpoint(shelf, titleRuns)
        val browseId = JsonTraversal.browseId(endpoint)
            .ifBlank { JsonTraversal.renderers(shelf.optJSONObject("onTap"), "browseEndpoint").firstOrNull()?.optString("browseId").orEmpty() }
            .ifBlank { JsonTraversal.renderers(shelf.optJSONObject("title"), "browseEndpoint").firstOrNull()?.optString("browseId").orEmpty() }
        val videoId = if (browseId.isBlank()) {
            JsonTraversal.videoId(endpoint)
                .ifBlank { JsonTraversal.renderers(shelf.optJSONObject("onTap"), "watchEndpoint").firstOrNull()?.optString("videoId").orEmpty() }
                .ifBlank { JsonTraversal.renderers(shelf.optJSONObject("title"), "watchEndpoint").firstOrNull()?.optString("videoId").orEmpty() }
        } else ""
        val pageType = JsonTraversal.pageType(endpoint)
            .ifBlank {
                JsonTraversal.renderers(shelf, "browseEndpointContextMusicConfig").firstOrNull()?.optString("pageType").orEmpty()
            }
        val art = JsonTraversal.largestThumbnail(shelf)
        if (videoId.isNotBlank()) {
            return CatalogItem.Song(track(videoId, title, listOf(title, subtitle), art, shelf))
        }
        return browsable(browseId, title, subtitle, art, pageType, shelf)
    }

    private fun responsive(renderer: JSONObject, fallbackArtist: String = ""): CatalogItem? {
        val columns = renderer.optJSONArray("flexColumns") ?: JSONArray()
        val texts = buildList {
            for (index in 0 until columns.length()) {
                val column = columns.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                add(JsonTraversal.text(column?.optJSONObject("text")))
            }
        }.filter(String::isNotBlank)
        val runs = JsonTraversal.runs(
            columns.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text"),
        )
        val endpoint = runs.firstOrNull()?.let(JsonTraversal::navigation) ?: JsonTraversal.navigation(renderer)
        val playable = renderer.preferredPlayable(endpoint)
        val videoId = playable.first
        val browseId = JsonTraversal.browseId(endpoint)
        val title = texts.firstOrNull().orEmpty()
        if (title.isBlank()) return null
        val art = JsonTraversal.largestThumbnail(renderer)
        if (videoId.isNotBlank()) {
            return CatalogItem.Song(track(videoId, title, texts, art, renderer, fallbackArtist, playable.second))
        }
        return browsable(browseId, title, texts.drop(1).joinToString(" • "), art, JsonTraversal.pageType(endpoint), renderer)
    }

    private fun twoRow(renderer: JSONObject): CatalogItem? {
        val titleRuns = JsonTraversal.runs(renderer.optJSONObject("title"))
        val title = JsonTraversal.text(renderer.optJSONObject("title"))
        if (title.isBlank()) return null
        val subtitle = JsonTraversal.text(renderer.optJSONObject("subtitle"))
        val endpoint = cardEndpoint(renderer, titleRuns)
        val browseId = JsonTraversal.browseId(endpoint)
        val playable = if (browseId.isBlank()) renderer.preferredPlayable(endpoint) else "" to ""
        val videoId = playable.first
        val art = JsonTraversal.largestThumbnail(renderer)
        if (videoId.isNotBlank()) {
            return CatalogItem.Song(
                track(videoId, title, listOf(title, subtitle), art, renderer, musicVideoType = playable.second),
            )
        }
        return browsable(browseId, title, subtitle, art, JsonTraversal.pageType(endpoint), renderer)
    }

    /**
     * Resolves the destination attached to a media card itself before considering its title.
     *
     * Artist album cards can carry a playable title endpoint as well as a direct album browse
     * endpoint. Treating the title as authoritative turns the album into a song (or sends an
     * unusable id to browse), even though tapping the card in YouTube Music opens the album. Menu
     * endpoints are deliberately excluded: only direct card actions participate here.
     */
    private fun cardEndpoint(renderer: JSONObject, titleRuns: List<JSONObject>): JSONObject? {
        val onTap = renderer.optJSONObject("onTap")
        val candidates = buildList {
            renderer.optJSONObject("navigationEndpoint")?.let(::add)
            renderer.optJSONObject("endpoint")?.let(::add)
            onTap?.takeIf { it.has("browseEndpoint") || it.has("watchEndpoint") }?.let(::add)
            onTap?.optJSONObject("navigationEndpoint")?.let(::add)
            onTap?.optJSONObject("endpoint")?.let(::add)
            titleRuns.mapNotNullTo(this) { run -> run.optJSONObject("navigationEndpoint") }
        }
        return candidates.firstOrNull { JsonTraversal.browseId(it).isNotBlank() }
            ?: candidates.firstOrNull { JsonTraversal.videoId(it).isNotBlank() }
            ?: JsonTraversal.navigation(renderer)
    }

    /**
     * Album and artist pages sweep several shelves for one track list, so the same song lands in
     * the result twice and has to be collapsed. A playlist is a literal ordered list: a song the
     * user added twice is two rows, and desktop shows it as two rows, so leave those alone.
     */
    private fun List<Track>.collapseDuplicates(kind: CatalogKind): List<Track> =
        if (kind == CatalogKind.PLAYLIST) this else distinctBy(Track::id).preferAlbumAudio()

    /**
     * An album page mixes its track list with video and related shelves, so the same song can
     * appear twice: once as album audio and once as the music video, which runs longer and would
     * throw off both playback and lyric timing. Keep the album cut when a title has both.
     */
    private fun List<Track>.preferAlbumAudio(): List<Track> {
        if (none { it.isVideoUpload }) return this
        val audioKeys = filter { it.isAudioOnly }.mapTo(mutableSetOf()) { it.sameSongKey() }
        return filterNot { it.isVideoUpload && it.sameSongKey() in audioKeys }
    }

    private fun Track.sameSongKey(): String =
        "${title.lowercase().trim()}|${artist.lowercase().trim()}"

    /**
     * Resolves the id a row should actually play, in the order SimpMusic uses.
     *
     * `playlistItemData` is the album/playlist's own entry, which is the album audio. The title
     * run's navigation endpoint can point at the music video instead: longer, with an intro, and
     * out of step with the lyrics. Preferring the run first is what made album rows play videos.
     *
     * Returns the chosen video id paired with its ATV/OMV/UGC classification, if known.
     */
    private fun JSONObject.preferredPlayable(primary: JSONObject?): Pair<String, String> {
        val playlistItemId = optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (playlistItemId.isNotBlank()) return playlistItemId to typeForVideoId(playlistItemId)

        val overlayEndpoint = optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
        JsonTraversal.videoId(overlayEndpoint).takeIf(String::isNotBlank)?.let {
            return it to JsonTraversal.musicVideoType(overlayEndpoint)
        }

        JsonTraversal.videoId(primary).takeIf(String::isNotBlank)?.let {
            return it to JsonTraversal.musicVideoType(primary)
        }

        val nested = JsonTraversal.renderers(this, "watchEndpoint")
            .firstOrNull { it.optString("videoId").isNotBlank() }
        val nestedId = nested?.optString("videoId").orEmpty()
        return nestedId to (nested?.musicVideoType().orEmpty())
    }

    /** Looks up the classification of an id wherever it appears among the row's watch endpoints. */
    private fun JSONObject.typeForVideoId(videoId: String): String =
        JsonTraversal.renderers(this, "watchEndpoint")
            .firstOrNull { it.optString("videoId") == videoId }
            ?.musicVideoType()
            .orEmpty()

    private fun JSONObject.musicVideoType(): String = optJSONObject("watchEndpointMusicSupportedConfigs")
        ?.optJSONObject("watchEndpointMusicConfig")
        ?.optString("musicVideoType")
        .orEmpty()

    /**
     * Album and artist links hang off the row's own endpoints, so a song knows where it came from
     * even when it was reached from search rather than an album page.
     */
    private fun JSONObject.linkedBrowseId(pageType: String, idPrefix: String): String =
        JsonTraversal.renderers(this, "navigationEndpoint")
            .firstNotNullOfOrNull { endpoint ->
                val browseId = JsonTraversal.browseId(endpoint)
                val type = JsonTraversal.pageType(endpoint)
                browseId.takeIf {
                    it.isNotBlank() && (type.contains(pageType, true) || it.startsWith(idPrefix))
                }
            }
            .orEmpty()

    private fun track(
        videoId: String,
        title: String,
        texts: List<String>,
        art: String,
        renderer: JSONObject,
        fallbackArtist: String = "",
        musicVideoType: String = "",
    ): Track {
        val artist = extractTrackArtist(renderer, texts, fallbackArtist)
        val subtitle = texts.drop(1).joinToString(" • ")
        val parts = subtitle.split(" • ").filter(String::isNotBlank)
        val duration = parts.lastOrNull()?.let(::durationMs) ?: 0
        val album = parts.firstOrNull {
            it != artist && it.isArtistCandidate()
        }.orEmpty()
        val explicit = isExplicit(renderer)
        val resolvedArt = art.ifBlank {
            if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
        }
        return Track(
            videoId,
            title,
            artist,
            album,
            albumId = renderer.linkedBrowseId("ALBUM", "MPRE"),
            artistId = renderer.linkedBrowseId("ARTIST", "UC"),
            artworkUrl = resolvedArt,
            durationMs = duration,
            explicit = explicit,
            musicVideoType = musicVideoType,
        )
    }

    private fun isExplicit(renderer: JSONObject?): Boolean {
        if (renderer == null) return false

        val badgeRenderers = JsonTraversal.renderers(renderer, "musicInlineBadgeRenderer") +
                JsonTraversal.renderers(renderer, "badgeRenderer")

        for (badge in badgeRenderers) {
            val icon = badge.optJSONObject("icon")
            val iconType = icon?.optString("iconType").orEmpty().ifBlank { badge.optString("iconType") }
            if (iconType.contains("EXPLICIT", ignoreCase = true)) return true

            val label = JsonTraversal.text(badge.optJSONObject("accessibilityData"))
                .ifBlank { JsonTraversal.text(badge.optJSONObject("accessibility")) }
                .ifBlank { badge.optString("label") }
                .ifBlank { badge.optString("tooltip") }
            if (label.contains("explicit", ignoreCase = true)) return true
        }

        val icons = JsonTraversal.renderers(renderer, "icon")
        for (icon in icons) {
            val iconType = icon.optString("iconType")
            if (iconType.contains("EXPLICIT", ignoreCase = true)) return true
        }

        val str = renderer.toString()
        if (str.contains("MUSIC_EXPLICIT_BADGE", ignoreCase = true) ||
            str.contains("OFFICIAL_EXPLICIT_BADGE", ignoreCase = true) ||
            str.contains("BADGE_STYLE_TYPE_EXPLICIT", ignoreCase = true) ||
            str.contains("EXPLICIT_BADGE", ignoreCase = true) ||
            str.contains("EXPLICIT", ignoreCase = true) ||
            str.contains("Explicit")
        ) {
            return true
        }

        return false
    }

    private fun extractHeaderArtist(header: JSONObject?): String {
        if (header == null) return ""
        val authorFields = listOf("author", "ownerText", "bylineText", "straplineText", "straplineTextOne", "strapline")
        val strapline = authorFields
            .firstNotNullOfOrNull { field ->
                val text = header.textField(field)
                text.takeIf { it.isArtistCandidate() }
            }.orEmpty()
        if (strapline.isNotBlank()) return strapline

        val allRuns = (authorFields + listOf("subtitle", "secondSubtitle", "title"))
            .flatMap { field -> JsonTraversal.runs(header.optJSONObject(field)) }
        val artistRun = allRuns.firstOrNull { run ->
            val endpoint = JsonTraversal.navigation(run) ?: run.optJSONObject("navigationEndpoint")
            val pageType = JsonTraversal.pageType(endpoint)
            val browseId = JsonTraversal.browseId(endpoint)
            pageType.contains("ARTIST", true) || pageType.contains("USER", true) || pageType.contains("CHANNEL", true) || browseId.startsWith("UC")
        }
        val artistFromRun = JsonTraversal.text(artistRun)
        if (artistFromRun.isNotBlank() && artistFromRun.isArtistCandidate()) return artistFromRun

        val subtitleArtist = playlistAuthor(header.textField("subtitle"))
            .ifBlank { playlistAuthor(header.textField("secondSubtitle")) }
            .ifBlank { playlistAuthor(header.textField("straplineTextOne")) }
            .ifBlank { playlistAuthor(header.textField("straplineText")) }
        if (subtitleArtist.isNotBlank()) return subtitleArtist

        return ""
    }

    private fun extractTrackArtist(renderer: JSONObject, texts: List<String>, fallbackArtist: String): String {
        val columns = renderer.optJSONArray("flexColumns") ?: JSONArray()
        for (i in 0 until columns.length()) {
            val col = columns.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            val runs = JsonTraversal.runs(col?.optJSONObject("text"))
            val artistRun = runs.firstOrNull { run ->
                val endpoint = JsonTraversal.navigation(run) ?: run.optJSONObject("navigationEndpoint")
                val pageType = JsonTraversal.pageType(endpoint)
                val browseId = JsonTraversal.browseId(endpoint)
                pageType.contains("ARTIST", true) || browseId.startsWith("UC")
            }
            if (artistRun != null) {
                val name = JsonTraversal.text(artistRun)
                if (name.isNotBlank() && name.isArtistCandidate()) return name
            }
        }

        val subtitle = texts.drop(1).joinToString(" • ")
        val parts = subtitle.split(" • ").map(String::trim).filter(String::isNotBlank)
        val candidate = parts.firstOrNull { it.isArtistCandidate() }
        if (!candidate.isNullOrBlank()) return candidate

        if (fallbackArtist.isNotBlank() && fallbackArtist.isArtistCandidate()) return fallbackArtist

        return "Unknown artist"
    }

    private fun browsable(id: String, title: String, subtitle: String, art: String, pageType: String, renderer: JSONObject? = null): CatalogItem? {
        if (id.isBlank()) return null
        val explicit = isExplicit(renderer)
        return when {
            // Ports desktop's isKnownArtistItem: plain user channels ride the same UC ids as
            // official artist channels, so page type is the only thing separating a real artist
            // from a random uploader. Drop the channels rather than shelve them as artists.
            "USER_CHANNEL" in pageType -> null
            "ARTIST" in pageType || id.startsWith("UC") -> CatalogItem.Performer(Artist(id, title, art, subtitle))
            "ALBUM" in pageType || id.startsWith("MPRE") ->
                CatalogItem.Record(Album(id, title, albumArtist(subtitle), art, releaseYear(subtitle), explicit = explicit))
            else -> CatalogItem.Collection(Playlist(id, title, playlistAuthor(subtitle).ifBlank { "YouTube Music" }, art, explicit = explicit))
        }
    }

    private fun playlistAuthor(subtitle: String): String {
        val parts = subtitle.split(" • ", "·", "•").map(String::trim).filter(String::isNotBlank)
        return parts.firstOrNull { it.isArtistCandidate() }.orEmpty()
    }

    private fun albumArtist(subtitle: String): String = playlistAuthor(subtitle)

    /**
     * The release year out of a shelf subtitle such as "Album • SZA • 2022".
     *
     * Blank when the row carries no year, which is common: singles shelves and
     * some recommendations ship a type label and nothing else. A four-digit
     * token is unambiguous here because [isArtistCandidate] has already refused
     * to treat a bare number as an artist.
     */
    private fun releaseYear(subtitle: String): String = subtitle
        .split(" • ", "·", "•")
        .map(String::trim)
        .firstOrNull { it.matches(Regex("^\\d{4}$")) }
        .orEmpty()

    /**
     * Ports desktop's collection context: tracks opened from an album carry the
     * album's identity, artist, and cover. Album rows usually ship no artwork of
     * their own, so they fall back to the song's video thumbnail; the album
     * cover always wins over that, mirroring Orchard desktop's album view.
     */
    private fun Track.withCollectionContext(
        kind: CatalogKind,
        detailId: String,
        detailTitle: String,
        detailArtist: String,
        detailArtwork: String,
    ): Track {
        if (kind != CatalogKind.ALBUM) {
            return if (artworkUrl.isBlank() && detailArtwork.isNotBlank()) copy(artworkUrl = detailArtwork) else this
        }
        val cover = when {
            detailArtwork.isBlank() -> artworkUrl
            artworkUrl.isBlank() || artworkUrl.isVideoThumbnail() -> detailArtwork
            else -> artworkUrl
        }
        return copy(
            album = album.ifBlank { detailTitle },
            albumId = albumId.ifBlank { detailId },
            artist = artist.ifBlank { detailArtist },
            artworkUrl = cover,
        )
    }

    /** YouTube video thumbnails are per-song stills, never the album cover. */
    private fun String.isVideoThumbnail(): Boolean = contains("i.ytimg.com/vi/")

    private fun inferDetailKind(id: String): CatalogKind = when {
        id.startsWith("UC") -> CatalogKind.ARTIST
        id.startsWith("MPRE") -> CatalogKind.ALBUM
        else -> CatalogKind.PLAYLIST
    }

    private fun durationMs(text: String): Long {
        val parts = text.trim().split(':').mapNotNull(String::toLongOrNull)
        if (parts.size !in 2..3) return 0
        return parts.fold(0L) { total, part -> total * 60 + part } * 1_000
    }

    private val visibilityLabels = setOf(
        "public", "unlisted", "private",
        "public playlist", "unlisted playlist", "private playlist", "community playlist",
    )

    private fun String.isVisibilityLabel(): Boolean = visibilityLabels.contains(trim().lowercase())

    private val typeLabels = setOf("song", "video", "episode", "album", "single", "ep", "playlist", "artist", "profile", "podcast")

    private fun String.isTypeLabel(): Boolean = typeLabels.contains(trim().lowercase())

    private fun String.isEngagementMetric(): Boolean = contains(
        Regex("\\b(?:plays?|views?|listeners?|subscribers?)\\b", RegexOption.IGNORE_CASE),
    )

    private fun String.isArtistCandidate(): Boolean {
        val trimmed = trim()
        if (trimmed.isBlank()) return false
        if (trimmed.isVisibilityLabel()) return false
        if (trimmed.isTypeLabel()) return false
        if (trimmed.isEngagementMetric()) return false
        if (trimmed.matches(Regex("^\\d+$"))) return false
        if (trimmed.matches(Regex("^\\d+\\s+(?:songs?|tracks?|items?)$", RegexOption.IGNORE_CASE))) return false
        if (trimmed.contains(Regex("\\b(?:hr|hour|hours|min|mins|minute|minutes|sec|secs|second|seconds)\\b", RegexOption.IGNORE_CASE))) return false
        if (durationMs(trimmed) > 0L) return false
        return true
    }

    private fun JSONObject?.textField(name: String): String = JsonTraversal.text(this?.optJSONObject(name))
}
