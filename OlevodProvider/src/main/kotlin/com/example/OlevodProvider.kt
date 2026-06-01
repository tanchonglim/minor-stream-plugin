package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
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

    // Listing page cards: <li class="vodlist_item num_N">
    //   <a class="vodlist_thumb" href="..." title="..." data-original="...jpg">
    //   <div class="vodlist_titbox"><p class="vodlist_title"><a>title</a>
    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("li[class^=vodlist_item]").mapNotNull { li ->
            val thumbEl = li.selectFirst("a.vodlist_thumb, a[href*=/vod/detail/]") ?: return@mapNotNull null
            val href = fixUrl(thumbEl.attr("href").ifEmpty { return@mapNotNull null })
            val title = thumbEl.attr("title").trim()
                .ifEmpty { li.selectFirst("p.vodlist_title")?.text()?.trim() ?: return@mapNotNull null }
            val posterUrl = fixUrlNull(
                thumbEl.attr("data-original").ifEmpty { null }
                    ?: thumbEl.selectFirst("img")?.attr("src")
            )
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Search results use a different card class: searchlist_item
    private fun parseSearchCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("li.searchlist_item").mapNotNull { li ->
            val href = fixUrl(li.selectFirst("a[href*=/vod/detail/]")?.attr("href") ?: return@mapNotNull null)
            val title = li.selectFirst("p.vodlist_title")?.text()?.trim()
                ?: li.selectFirst("a[href*=/vod/detail/]")?.attr("title")?.trim()
                ?: return@mapNotNull null
            val posterUrl = fixUrlNull(
                li.selectFirst("a[data-original]")?.attr("data-original")
                    ?: li.selectFirst("img")?.attr("src")
            )
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl${request.data}"
        } else {
            val base = request.data.removeSuffix(".html")
            "$mainUrl${base}/page/$page.html"
        }
        Log.d("OlevodProvider", "getMainPage url=$url")
        val document = app.get(url, headers = headers).document
        val items = parseCards(document)
        Log.d("OlevodProvider", "getMainPage items=${items.size}")
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php/vod/search/wd/$query.html"
        Log.d("OlevodProvider", "search url=$url")
        val document = app.get(url, headers = headers).document
        val results = parseSearchCards(document)
        Log.d("OlevodProvider", "search results=${results.size}")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("OlevodProvider", "load url=$url")
        val document = app.get(url, headers = headers).document

        // Title is in <title>: "{name}_超清欧乐影院 - 欧乐影院..." or "{name}_..."
        val rawTitle = document.title()
        val title = rawTitle.substringBefore("_").substringBefore(" - ").trim()
            .ifEmpty { throw ErrorLoadingException("No title found at $url") }
        Log.d("OlevodProvider", "load title=$title")

        // Poster: first image in a[data-original] or img on the detail page
        val posterUrl = fixUrlNull(
            document.selectFirst("a[data-original]")?.attr("data-original")
                ?: document.selectFirst("img[src*=upload]")?.attr("src")
        )

        // Metadata from list items with span labels
        var year: Int? = null
        var tags: List<String>? = null
        var plot: String? = null
        val actors = mutableListOf<String>()

        document.select("li.data, p.data, li, p").forEach { el ->
            val text = el.text().trim()
            when {
                (text.contains("年份") || text.contains("年代")) && year == null ->
                    year = el.selectFirst("a")?.text()?.trim()?.toIntOrNull()
                        ?: Regex("\\d{4}").find(text)?.value?.toIntOrNull()
                (text.contains("类型") || text.contains("分类")) && tags == null ->
                    tags = el.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
                (text.contains("主演") || text.contains("演员")) && actors.isEmpty() ->
                    actors.addAll(el.select("a").map { it.text().trim() }.filter { it.isNotEmpty() })
                (text.contains("简介") || text.contains("剧情")) && plot == null ->
                    plot = el.selectFirst("span.desc, span.info_right, span")?.text()?.trim()
                        ?: text.substringAfter("：").substringAfter(":").trim().ifEmpty { null }
            }
        }

        // Episode list: a[href*=/vod/play/id/], data = "vodId:::sid:::nid"
        val episodes = document.select("a[href*=/index.php/vod/play/id/]").mapNotNull { el ->
            val m = playHrefRegex.find(el.attr("href")) ?: return@mapNotNull null
            val (vodId, sid, nid) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val epName = el.text().trim().ifEmpty { "第${nid}集" }
            newEpisode("$vodId:::$sid:::$nid") {
                this.name = epName
                this.episode = nid.toIntOrNull()
            }
        }.distinctBy { it.data }
        Log.d("OlevodProvider", "load episodes=${episodes.size}")

        return if (episodes.size <= 1) {
            val dataStr = episodes.firstOrNull()?.data ?: ""
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
        // data: "vodId:::sid:::nid"
        val parts = data.split(":::")
        if (parts.size < 3) {
            Log.w("OlevodProvider", "loadLinks invalid data: $data")
            return false
        }
        val playUrl = "$mainUrl/index.php/vod/play/id/${parts[0]}/sid/${parts[1]}/nid/${parts[2]}.html"
        Log.d("OlevodProvider", "loadLinks playUrl=$playUrl")

        val html = try {
            app.get(playUrl, headers = headers).text
        } catch (e: Exception) {
            Log.e("OlevodProvider", "loadLinks fetch failed: ${e.message}")
            return false
        }

        val (videoUrl, flag) = extractPlayerAaaa(html) ?: run {
            Log.w("OlevodProvider", "loadLinks player_aaaa not found in $playUrl")
            return false
        }
        Log.d("OlevodProvider", "loadLinks videoUrl=$videoUrl flag=$flag")
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
