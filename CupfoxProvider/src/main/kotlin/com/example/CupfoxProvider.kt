package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

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

    // /vod-play/{vodId}-{sourceId}-{epNum}.html
    private val playHrefRegex = Regex("""/vod-play/(\d+)-(\d+)-(\d+)\.html""")

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
        val document = app.get(url, headers = headers).document
        val items = parseCards(document)
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query", headers = headers).document
        return parseCards(document)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" - ").trim().ifEmpty {
                throw ErrorLoadingException("No title found")
            }

        val vodIdFromUrl = Regex("""/vod-detail/(\d+)""").find(url)?.groupValues?.get(1)
        val posterUrl = fixUrlNull(
            document.selectFirst("img[src*=/uimg/]")?.attr("src")
                ?: vodIdFromUrl?.let { "$mainUrl/uimg/$it.jpg" }
        )

        var year: Int? = null
        var tags: List<String>? = null
        var plot: String? = null
        val actors = mutableListOf<String>()

        document.select("p, li, .info-item").forEach { el ->
            val text = el.text().trim()
            when {
                (text.startsWith("年份") || text.startsWith("年代")) && year == null ->
                    year = Regex("\\d{4}").find(text)?.value?.toIntOrNull()
                (text.startsWith("类型") || text.startsWith("分类")) && tags == null ->
                    tags = el.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
                text.startsWith("主演") || text.startsWith("演员") ->
                    actors.addAll(el.select("a").map { it.text().trim() }.filter { it.isNotEmpty() })
                (text.startsWith("简介") || text.startsWith("剧情")) && plot == null ->
                    plot = text.substringAfter("：").substringAfter(":").trim()
            }
        }

        // Parse episodes from play links; data = "vodId:::sourceId:::epNum"
        val episodes = document.select("a[href*=/vod-play/]").mapNotNull { el ->
            val m = playHrefRegex.find(el.attr("href")) ?: return@mapNotNull null
            val (vodId, sid, ep) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val epName = el.text().trim().ifEmpty { "第${ep}集" }
            newEpisode("$vodId:::$sid:::$ep") {
                this.name = epName
                this.episode = ep.toIntOrNull()
            }
        }.distinctBy { it.data }

        return if (episodes.size <= 1) {
            val dataStr = episodes.firstOrNull()?.data ?: "$vodIdFromUrl:::1:::1"
            newMovieLoadResponse(title, url, TvType.Movie, dataStr) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
            }
        } else {
            val type = if (tags?.any { it.contains("动漫") } == true) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data: "vodId:::sourceId:::epNum"
        val parts = data.split(":::")
        if (parts.size < 3) return false
        val playUrl = "$mainUrl/vod-play/${parts[0]}-${parts[1]}-${parts[2]}.html"

        val html = try {
            app.get(playUrl, headers = headers).text
        } catch (_: Exception) {
            return false
        }

        val (videoUrl, flag) = extractPlayerAaaa(html) ?: return false
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name - ${flag.uppercase()}",
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    // Intercepts the player_aaaa JS config embedded in the play page HTML.
    // Returns Pair(videoUrl, flagName) or null if not found.
    private fun extractPlayerAaaa(html: String): Pair<String, String>? {
        val markerIdx = html.indexOf("player_aaaa")
        if (markerIdx == -1) return null
        val start = html.indexOf('{', markerIdx)
        if (start == -1) return null

        var depth = 0; var end = -1; var inStr = false; var esc = false
        for (i in start until html.length) {
            val c = html[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            when (c) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end == -1) return null
        val blob = html.substring(start, end + 1)

        val urlMatch = Regex(""""?url"?\s*:\s*"([^"]*)"""").find(blob) ?: return null
        var url = urlMatch.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")

        val encrypt = Regex(""""?encrypt"?\s*:\s*(\d+)""").find(blob)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        url = when (encrypt) {
            1 -> try { String(Base64.decode(url, Base64.DEFAULT)) } catch (_: Exception) { url }
            2 -> try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
            else -> url
        }
        if (url.isBlank()) return null

        val flag = Regex(""""?(?:flag|from)"?\s*:\s*"([^"]+)"""").find(blob)?.groupValues?.get(1) ?: "线路1"
        return url to flag
    }
}
