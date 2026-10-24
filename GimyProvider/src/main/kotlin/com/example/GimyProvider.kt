package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class GimyProvider : MainAPI() {
    override var mainUrl = "https://gimy.se"
    override var name = "Gimy"
    override var lang = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Documentary
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    // MacCMS type ids; listing uses /vodshow/{type}--------{page}---.html
    override val mainPage = mainPageOf(
        "2" to "電視劇",
        "1" to "電影",
        "3" to "綜藝",
        "4" to "動漫",
        "23" to "紀錄片",
        "54" to "短劇",
    )

    private val videoHrefRegex = Regex("/video/(\\d+)-(\\d+)\\.html")
    private val vodIdRegex = Regex("/voddetail/(\\d+)\\.html")
    private val bgUrlRegex = Regex("""url\(([^)]+)\)""")
    private val m3u8Regex = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")

    private fun typeFromId(typeId: Int?): TvType = when (typeId) {
        24 -> TvType.AnimeMovie
        1, 6, 7, 8, 9, 10, 11, 12 -> TvType.Movie
        4 -> TvType.Anime
        23 -> TvType.Documentary
        else -> TvType.TvSeries
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").takeIf { vodIdRegex.containsMatchIn(it) } ?: return null
        val title = attr("title").trim().ifEmpty { return null }
        val poster = attr("data-original").ifBlank { null }
            ?: selectFirst("img")?.attr("src")?.ifBlank { null }
            ?: bgUrlRegex.find(attr("style"))?.groupValues?.get(1)?.trim('\'', '"')
        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/vodshow/${request.data}--------$page---.html"
        val document = app.get(url, headers = headers).document
        val items = document.select("li a.video-pic")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/vodsearch/-------------.html?wd=$q", headers = headers).document
        return document.select(".news-box-txt-l a.video-pic")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private data class SourceEp(val sid: String, val sourceName: String, val nid: Int, val label: String)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val vodId = vodIdRegex.find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Invalid url")

        val title = document.selectFirst(".details-info h1")?.text()?.trim()
            ?: throw ErrorLoadingException("No title found")
        val poster = fixUrlNull(document.selectFirst("img.details-poster-image")?.attr("src"))

        // Meta grid: label span + value element per <li>
        val meta = document.select("li.gimy-detail-meta-item").associate { li ->
            val label = li.selectFirst(".gimy-detail-meta-label")?.text()?.trim().orEmpty()
            label to li.selectFirst(".gimy-detail-meta-value")
        }
        val year = meta["年代"]?.text()?.trim()?.toIntOrNull()
        val categoryLink = meta["類別"]
        val tags = categoryLink?.text()?.trim()?.let { listOf(it) }
        val typeId = Regex("/vodtype/(\\d+)").find(categoryLink?.attr("href").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val actors = meta["主演"]?.text()?.split("、", ",", "/")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
        // Plot is prefixed with "{title}線上看:{category},"
        val plot = document.selectFirst(".details-content-all")?.text()?.trim()
            ?.replace(Regex("^.*?線上看:[^,，]*[,，]"), "")?.trim()

        // Source tabs: href="#con_playlist_{sid}" → name
        val sourceNames = document.select("a[href^='#con_playlist_']").associate {
            it.attr("href").removePrefix("#con_playlist_") to it.text().trim()
        }

        // Each playlist <ul> appears twice (mobile + desktop); keep the first per sid
        val sourceEps = document.select("ul[id^=con_playlist_]")
            .distinctBy { it.id() }
            .flatMap { ul ->
                val sid = ul.id().removePrefix("con_playlist_")
                val sourceName = sourceNames[sid] ?: "線路$sid"
                ul.select("a[href*=/video/]").mapNotNull { a ->
                    val nid = videoHrefRegex.find(a.attr("href"))?.groupValues?.get(2)?.toIntOrNull()
                        ?: return@mapNotNull null
                    val label = a.selectFirst(".episode-title")?.text()?.trim()
                        ?: a.text().trim().takeIf { it.isNotEmpty() && it != "▶" }
                        ?: "第${nid}集"
                    SourceEp(sid, sourceName, nid, label)
                }
            }

        // Group the same nid across sources so loadLinks can try every line
        val episodes = sourceEps.groupBy { it.nid }.toSortedMap().map { (nid, eps) ->
            val sources = eps.distinctBy { it.sid }.joinToString(",") { "${it.sid}~${it.sourceName}" }
            newEpisode("$vodId:::$nid:::$sources") {
                this.name = eps.first().label
                this.episode = nid
            }
        }

        val type = typeFromId(typeId)
        val recommendations = document.select("li a.video-pic")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return if ((type == TvType.Movie || type == TvType.AnimeMovie) && episodes.size <= 1) {
            newMovieLoadResponse(title, url, type, episodes.firstOrNull()?.data ?: "$vodId:::1:::") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors?.map { ActorData(Actor(it)) }
            }
        } else {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors?.map { ActorData(Actor(it)) }
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayerAaaa(
        @JsonProperty("url") val url: String?,
        @JsonProperty("encrypt") val encrypt: Int?,
        @JsonProperty("from") val from: String?,
        @JsonProperty("sid") val sid: Int?,
    )

    /** Extracts the `player_aaaa={...}` JSON blob by walking braces. */
    private fun extractPlayerAaaa(html: String): PlayerAaaa? {
        val markerIdx = html.indexOf("player_aaaa")
        if (markerIdx < 0) return null
        val start = html.indexOf('{', markerIdx)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return tryParseJson<PlayerAaaa>(html.substring(start, i + 1))
            }
            i++
        }
        return null
    }

    private fun decodeUrl(player: PlayerAaaa): String? {
        val raw = player.url?.takeIf { it.isNotBlank() } ?: return null
        return when (player.encrypt) {
            1 -> URLDecoder.decode(raw, "UTF-8")
            2 -> URLDecoder.decode(base64Decode(raw), "UTF-8")
            else -> raw
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: "{vodId}:::{nid}:::{sid}~{name},{sid}~{name},..."
        val parts = data.removePrefix(mainUrl).removePrefix("/").split(":::")
        val vodId = parts.getOrNull(0) ?: return false
        val nid = parts.getOrNull(1) ?: return false
        val sources = parts.getOrNull(2).orEmpty().split(",").filter { it.isNotBlank() }
            .map { it.substringBefore("~") to it.substringAfter("~", "") }
            .ifEmpty { listOf("1" to "") }

        val playUrl = "$mainUrl/video/$vodId-$nid.html"
        var found = false

        // The site selects the source line via a "sid" cookie on the same play URL
        sources.amap { (sid, sourceName) ->
            val player = try {
                extractPlayerAaaa(app.get(playUrl, headers = headers, cookies = mapOf("sid" to sid)).text)
            } catch (_: Exception) {
                null
            } ?: return@amap
            // Server falls back to another sid if the requested one doesn't exist
            if (player.sid != null && player.sid.toString() != sid) return@amap
            val url = decodeUrl(player) ?: return@amap

            val m3u8 = if (url.contains(".m3u8")) {
                url
            } else {
                // "網頁" sources are embed pages; pull the first m3u8 out of them
                try {
                    m3u8Regex.find(app.get(url, headers = headers, referer = playUrl).text)?.value
                } catch (_: Exception) {
                    null
                }
            } ?: return@amap

            val label = sourceName.ifBlank { player.from ?: "線路$sid" }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name - $label",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }
}
