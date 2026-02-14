package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}?page=$page"
        }
        val document = app.get(url).document
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
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("div.row.my-gutters-1 > div").mapNotNull { card ->
            card.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

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

        // Parse episode list
        val episodes = document.select("ul.list-unstyled a.ep-btn").mapNotNull { epElement ->
            val epHref = epElement.attr("href") ?: return@mapNotNull null
            val epName = epElement.text().trim()
            // Extract play data from href like /play/202632236-ep1 or /play/202681548-m#yu_gao_pian
            val playData = epHref.split("#").first() // Remove fragment
            val match = Regex("/play/(\\d+)-(.+)").find(playData) ?: return@mapNotNull null
            val id = match.groupValues[1]
            val suffix = match.groupValues[2]
            // For ep links: pass "id:::ep1", for non-ep links (like "m"): pass just "id"
            val episodeData = if (suffix.startsWith("ep")) "$id:::$suffix" else id
            val epNum = if (suffix.startsWith("ep")) {
                suffix.removePrefix("ep").toIntOrNull()
            } else null
            newEpisode(episodeData) {
                this.name = epName
                if (epNum != null) {
                    this.episode = epNum
                }
            }
        }

        // Sort episodes by number (they come in descending order on the page)
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

    data class VideoPlay(
        @JsonProperty("play_data") val playData: String?,
        @JsonProperty("src_site") val srcSite: String?
    )

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
        val apiUrl = if (data.contains(":::")) {
            val parts = data.split(":::")
            "$mainUrl/vod_plays/${parts[0]}/${parts[1]}"
        } else {
            "$mainUrl/vod_plays/$data/"
        }

        val response = app.get(apiUrl).parsed<VodPlaysResponse>()
        val plays = response.videoPlays ?: return false

        plays.forEachIndexed { index, play ->
            val m3u8Url = play.playData ?: return@forEachIndexed
            val sourceName = play.srcSite?.uppercase() ?: "Source ${index + 1}"
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
