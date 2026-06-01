package com.example

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class CupfoxProvider : MainAPI() {
    override var mainUrl = "https://cupfox.in"
    override var name = "Cupfox"
    override var lang = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "tv" to "电视剧",
        "movie" to "电影",
        "anime" to "动漫",
        "show" to "综艺",
    )

    // Intercepted from the site JS: GET /tea/{vodId}-{epSlug} → {video_plays:[{play_data,src_site}]}
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TeaResponse(
        @JsonProperty("video_plays") val videoPlays: List<VideoPlay>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoPlay(
        @JsonProperty("play_data") val playData: String?,
        @JsonProperty("src_site") val srcSite: String?
    )

    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        document.select("a[href*=/vod-detail/]").forEach { el ->
            val href = fixUrl(el.attr("href"))
            if (!seen.add(href)) return@forEach
            val imgEl = el.selectFirst("img") ?: return@forEach
            val title = imgEl.attr("alt").trim().ifEmpty { return@forEach }
            val posterUrl = fixUrlNull(imgEl.attr("src").ifEmpty { null })
            results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            })
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/filter/?type=${request.data}&pg=$page"
        Log.d("CupfoxProvider", "getMainPage url=$url")
        val document = app.get(url, headers = headers).document
        val items = parseCards(document)
        Log.d("CupfoxProvider", "getMainPage items=${items.size}")
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        Log.d("CupfoxProvider", "search url=$url")
        val document = app.get(url, headers = headers).document
        return parseCards(document)
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("CupfoxProvider", "load url=$url")
        val document = app.get(url, headers = headers).document

        // Title is in <title>: "{name} 在线观看 - 茶杯狐 Cupfox"
        val rawTitle = document.title()
        val title = rawTitle.substringBefore(" 在线观看").substringBefore(" - 茶杯狐").trim()
            .ifEmpty { throw ErrorLoadingException("No title found at $url") }
        Log.d("CupfoxProvider", "load title=$title")

        val vodId = Regex("""/vod-detail/(\d+)""").find(url)?.groupValues?.get(1)
        val posterUrl = fixUrlNull(vodId?.let { "$mainUrl/uimg/$it.jpg" })
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // Episode list: <span class="play-btn" ep_slug="ep1">第01集</span>
        // JS initialises by picking the first play-btn and calling on_ep(ep_slug, text)
        val playBtns = document.select("span[class*=play-btn]")
        Log.d("CupfoxProvider", "load playBtns=${playBtns.size}")

        val episodes = playBtns.mapIndexedNotNull { index, el ->
            val epSlug = el.attr("ep_slug").trim()
            val epText = el.text().trim().ifEmpty { "第${index + 1}集" }
            // Sort key: extract number from epSlug (ep8 → 8); fall back to index
            val epNum = Regex("(\\d+)").find(epSlug)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
            newEpisode("$vodId:::$epSlug") {
                this.name = epText
                this.episode = epNum
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return if (episodes.size <= 1) {
            val dataStr = episodes.firstOrNull()?.data ?: "$vodId:::"
            newMovieLoadResponse(title, url, TvType.Movie, dataStr) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data: "vodId:::epSlug"  (epSlug may be empty for default version)
        val parts = data.split(":::")
        val vodId = parts[0].ifEmpty { return false }
        val epSlug = parts.getOrNull(1) ?: ""

        // Intercept the same /tea/ API the page JS calls to resolve video sources
        val apiUrl = if (epSlug.isNotEmpty()) "$mainUrl/tea/$vodId-$epSlug"
                     else "$mainUrl/tea/$vodId"
        Log.d("CupfoxProvider", "loadLinks apiUrl=$apiUrl")

        val response = try {
            app.get(apiUrl, headers = headers).parsed<TeaResponse>()
        } catch (e: Exception) {
            Log.e("CupfoxProvider", "loadLinks fetch failed: ${e.message}")
            return false
        }

        val plays = response.videoPlays
        Log.d("CupfoxProvider", "loadLinks video_plays=${plays?.size}")
        if (plays.isNullOrEmpty()) return false

        plays.forEach { play ->
            val m3u8 = play.playData?.trim() ?: return@forEach
            if (m3u8.isBlank()) return@forEach
            val srcName = play.srcSite?.uppercase() ?: "线路"
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - $srcName",
                    url = m3u8,
                    type = if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
