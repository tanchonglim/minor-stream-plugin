package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder

class OlevodProvider : MainAPI() {
    override var mainUrl = "https://olevod.com"
    override var name = "Olevod"
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
        "/index.php/vod/type/id/1.html" to "电影",
        "/index.php/vod/type/id/2.html" to "电视剧",
        "/index.php/vod/type/id/3.html" to "综艺",
        "/index.php/vod/type/id/4.html" to "动漫",
        "/index.php/vod/type/id/14.html" to "短剧",
    )

    // /index.php/vod/play/id/{vodId}/sid/{sid}/nid/{nid}.html
    private val playHrefRegex = Regex("""/play/id/(\d+)/sid/(\d+)/nid/(\d+)""")

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h2 a, h4 a") ?: return null
        val title = titleEl.text().trim().ifEmpty { return null }
        val href = fixUrl(titleEl.attr("href"))
        val posterUrl = fixUrlNull(
            this.selectFirst("a.vod-pic img, img")?.attr("data-src")
                ?: this.selectFirst("a.vod-pic img, img")?.attr("src")
        )
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl${request.data}"
        } else {
            val base = request.data.removeSuffix(".html")
            "$mainUrl${base}/page/$page.html"
        }
        val document = app.get(url, headers = headers).document
        val items = document.select(".vod-item, li.col-md-3, li.col-sm-3").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/index.php/vod/search/wd/$query.html", headers = headers).document
        return document.select(".vod-item, li.col-md-3, li.col-sm-3").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("No title found")

        val posterUrl = fixUrlNull(
            document.selectFirst(".vod-info img, .detail-pic img")?.attr("data-src")
                ?: document.selectFirst(".vod-info img, .detail-pic img")?.attr("src")
        )

        var year: Int? = null
        var tags: List<String>? = null
        var plot: String? = null
        val actors = mutableListOf<String>()

        document.select(".vod-info p, .detail-info p").forEach { p ->
            val text = p.text().trim()
            when {
                (text.contains("年份") || text.contains("年代")) && year == null ->
                    year = p.selectFirst("a")?.text()?.trim()?.toIntOrNull()
                        ?: Regex("\\d{4}").find(text)?.value?.toIntOrNull()
                (text.contains("类型") || text.contains("分类")) && tags == null ->
                    tags = p.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
                text.contains("主演") || text.contains("演员") ->
                    actors.addAll(p.select("a").map { it.text().trim() }.filter { it.isNotEmpty() })
                (text.contains("简介") || text.contains("剧情")) && plot == null ->
                    plot = text.substringAfter("：").substringAfter(":").trim()
            }
        }
        if (plot.isNullOrBlank()) {
            plot = document.selectFirst(".vod-desc, .detail-desc")?.text()?.trim()
        }

        // Parse episodes; data = "vodId:::sid:::nid"
        val episodes = document.select("a[href*=/index.php/vod/play/id/]").mapNotNull { el ->
            val m = playHrefRegex.find(el.attr("href")) ?: return@mapNotNull null
            val (vodId, sid, nid) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val epName = el.text().trim().ifEmpty { "第${nid}集" }
            newEpisode("$vodId:::$sid:::$nid") {
                this.name = epName
                this.episode = nid.toIntOrNull()
            }
        }.distinctBy { it.data }

        val recommendations = document.select(".vod-item, li.col-md-3").mapNotNull { it.toSearchResult() }

        return if (episodes.size <= 1) {
            val dataStr = episodes.firstOrNull()?.data ?: ""
            newMovieLoadResponse(title, url, TvType.Movie, dataStr) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors.map { ActorData(Actor(it)) }
            }
        } else {
            val type = if (tags?.any { it.contains("动漫") } == true) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
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
        // data: "vodId:::sid:::nid"
        val parts = data.split(":::")
        if (parts.size < 3) return false
        val playUrl = "$mainUrl/index.php/vod/play/id/${parts[0]}/sid/${parts[1]}/nid/${parts[2]}.html"

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
