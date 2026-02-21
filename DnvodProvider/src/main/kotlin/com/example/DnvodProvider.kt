package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class DnvodProvider : MainAPI() {
    override var mainUrl = "https://dnvod.org"
    override var name = "Dnvod"
    override var lang = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Documentary
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "/tv/list/" to "电视剧",
        "/movie/list/" to "电影",
        "/anime/list/" to "动漫",
        "/show/list/" to "综艺",
        "/doc/list/" to "纪录片",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        // Each card is a div containing two <a> tags:
        // First <a> wraps the image, second <a> has title and metadata
        val linkElements = this.select("a[href*=/detail/]")
        if (linkElements.isEmpty()) return null

        val href = fixUrl(linkElements.first()!!.attr("href"))
        val title = this.selectFirst("div.text-left.text-truncate.text-dark")?.text()?.trim()
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val type = getTypeFromUrl(href)

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun getTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/movie/") -> TvType.Movie
            url.contains("/tv/") -> TvType.TvSeries
            url.contains("/anime/") -> TvType.Anime
            url.contains("/doc/") -> TvType.Documentary
            url.contains("/show/") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    // Spec: (play|vod_plays)/([^#?]+) → segment; segment with -ep\d+ → id:::suffix, else id
    private val playOrVodHrefRegex = Regex("/(play|vod_plays)/([^#?]+)")
    private val segmentEpRegex = Regex("(\\d+)-(.+)", RegexOption.IGNORE_CASE)
    private val playLinkFallbackRegex = Regex("/play/(\\d+)-ep(\\d+)", RegexOption.IGNORE_CASE)

    /** Parse episode list per movie_link_logic: primary container, then fallback play links, then default single episode. */
    private fun parseEpisodes(document: org.jsoup.nodes.Document, detailUrl: String): List<Episode> {
        // 1. Primary: .row.list-unstyled.my-gutters-2, direct children, a[href*=/play/] or a[href*=/vod_plays/]
        val container = document.selectFirst(".row.list-unstyled.my-gutters-2")
        if (container != null) {
            val fromContainer = container.children().mapIndexedNotNull { index, child ->
                val anchor = child.selectFirst("a[href*=/play/], a[href*=/vod_plays/]") ?: return@mapIndexedNotNull null
                val href = anchor.attr("href").split("#").first().trim()
                val segmentMatch = playOrVodHrefRegex.find(href) ?: return@mapIndexedNotNull null
                val segment = segmentMatch.groupValues[2]
                val label = anchor.text().trim().ifEmpty { "第${index + 1}集" }
                val sid = Regex("-ep(\\d+)", RegexOption.IGNORE_CASE).find(segment)?.groupValues?.get(1)?.let { "ep$it" }
                    ?: "ep${index + 1}"
                val (episodeData, epNum) = segmentToEpisodeData(segment)
                newEpisode(episodeData) {
                    this.name = label
                    if (epNum != null) this.episode = epNum
                }
            }
            if (fromContainer.isNotEmpty()) return fromContainer
        }

        // 2. Fallback: a[href*=/play/] with /play/(\d+)-ep(\d+)
        val fallbackLinks = document.select("a[href*=/play/]")
        val fromFallback = fallbackLinks.mapNotNull { anchor ->
            val href = anchor.attr("href").split("#").first()
            val match = playLinkFallbackRegex.find(href) ?: return@mapNotNull null
            val vodId = match.groupValues[1]
            val epIndex = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val label = anchor.text().trim().ifEmpty { "第${epIndex}集" }
            newEpisode("$vodId:::ep$epIndex") {
                this.name = label
                this.episode = epIndex
            }
        }.distinctBy { it.data }
        if (fromFallback.isNotEmpty()) return fromFallback

        // 3. Default: single episode (vodId from detail URL)
        val vodId = Regex("/(\\d+)(?:/)?$").find(detailUrl)?.groupValues?.get(1) ?: return emptyList()
        return listOf(
            newEpisode("$vodId:::ep1") {
                this.name = "第1集"
                this.episode = 1
            }
        )
    }

    private fun segmentToEpisodeData(segment: String): Pair<String, Int?> {
        val m = segmentEpRegex.find(segment) ?: return (Regex("^(\\d+)").find(segment)?.groupValues?.get(1) ?: segment) to null
        val id = m.groupValues[1]
        val suffix = m.groupValues[2]
        return if (suffix.startsWith("ep", ignoreCase = true)) {
            val num = suffix.drop(2).toIntOrNull()
            "$id:::$suffix" to num
        } else {
            id to null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}?page=$page"
        }
        val document = app.get(url, headers = headers).document
        val items = document.select("div.row.my-gutters-1 > div").mapNotNull { card ->
            card.toSearchResult()
        }
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
        val document = app.get("$mainUrl/search?q=$query", headers = headers).document
        return document.select("div.row.my-gutters-1 > div").mapNotNull { card ->
            card.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1.title")?.text()?.trim()
            ?: throw ErrorLoadingException("No title found")
        val posterUrl = fixUrlNull(document.selectFirst("div.video.detail img")?.attr("src"))

        // Parse metadata
        val metadataDiv = document.selectFirst("div.pl-2.text-secondary")
        var year: Int? = null
        var tags: List<String>? = null

        metadataDiv?.select("div.mb-2")?.forEach { div ->
            val text = div.text().trim()
            when {
                text.startsWith("分类：") -> {
                    tags = text.removePrefix("分类：").split("/").map { it.trim() }.filter { it.isNotEmpty() }
                }
                text.startsWith("年份：") -> {
                    year = text.removePrefix("年份：").trim().toIntOrNull()
                }
            }
        }

        // Parse actors
        val introDiv = document.selectFirst("#intro")
        val actorText = introDiv?.selectFirst("span:contains(主演：)")?.parent()?.text()
            ?.removePrefix("主演：")?.trim()
        val actors = actorText?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() }

        // Parse plot
        val plot = introDiv?.selectFirst("small.text-secondary")?.text()?.trim()

        // Parse episode list (spec: container → fallback play links → default single episode)
        val episodes = parseEpisodes(document, url)
        val sortedEpisodes = episodes.sortedBy { it.episode ?: Int.MAX_VALUE }

        val type = getTypeFromUrl(url)

        // Parse recommendations
        val recommendations = document.select("span.dn-title-0:contains(推薦)")
            .parents().first()
            ?.select("div.row.my-gutters-1 > div")
            ?.mapNotNull { it.toSearchResult() }

        return if (type == TvType.Movie && sortedEpisodes.size <= 1) {
            // Extract movie play data (just the ID, non-ep links use /vod_plays/{id}/)
            val dataUrl = if (sortedEpisodes.isNotEmpty()) {
                sortedEpisodes.first().data
            } else {
                // Fallback: extract ID from URL
                Regex("/(\\d+)$").find(url)?.groupValues?.get(1) ?: ""
            }
            newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors?.map { ActorData(Actor(it)) }
            }
        } else {
            newTvSeriesLoadResponse(title, url, type, sortedEpisodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors?.map { ActorData(Actor(it)) }
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoPlay(
        @JsonProperty("play_data") val playData: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("src_site") val srcSite: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VodPlaysResponse(
        @JsonProperty("video_plays") val videoPlays: List<VideoPlay>?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: "id:::ep1" for episodes, or just "id" for movies/non-ep content
        // Strip mainUrl prefix if present (CloudStream may prepend it)
        val cleanData = data.removePrefix(mainUrl).removePrefix("/")

        val (apiUrl, refererUrl) = if (cleanData.contains(":::")) {
            val parts = cleanData.split(":::")
            "$mainUrl/vod_plays/${parts[0]}/${parts[1]}/" to "$mainUrl/play/${parts[0]}-${parts[1]}"
        } else {
            "$mainUrl/vod_plays/$cleanData/" to "$mainUrl/play/$cleanData"
        }

        val response = try {
            app.get(apiUrl, headers = headers + mapOf("Referer" to refererUrl)).parsed<VodPlaysResponse>()
        } catch (_: Exception) {
            return false
        }
        val plays = response.videoPlays ?: return false

        plays.forEachIndexed { index, play ->
            val m3u8Url = play.playData ?: return@forEachIndexed
            if (m3u8Url.isBlank()) return@forEachIndexed
            val sourceName = play.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: play.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: play.srcSite?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
                ?: "线路 ${index + 1}"
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name - $sourceName",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return plays.isNotEmpty()
    }
}
