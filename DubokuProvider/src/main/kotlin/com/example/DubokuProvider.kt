package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class DubokuProvider : MainAPI() {
    override var mainUrl = "https://duboku.info"
    override var name = "Duboku"
    override var lang = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "/vodshow/13-----------.html" to "陆剧",
        "/vodshow/16-----------.html" to "日韩剧",
        "/vodshow/14-----------.html" to "港台剧",
        "/vodshow/15-----------.html" to "欧美剧",
        "/vodshow/1-----------.html" to "电影",
        "/vodshow/3-----------.html" to "动漫",
        "/vodshow/4-----------.html" to "综艺",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h4 a, .myui-vodlist__detail h4 a") ?: return null
        val title = titleEl.text().trim()
        val href = fixUrl(titleEl.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a.myui-vodlist__thumb img, img")?.attr("data-original"))
            ?: fixUrlNull(this.selectFirst("a.myui-vodlist__thumb")?.attr("data-original"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl${request.data}"
        } else {
            val base = request.data.replace("-----------.html", "")
            "$mainUrl${base}--------${page}---.html"
        }
        val document = app.get(url, headers = headers).document
        val items = document.select(".myui-vodlist li").mapNotNull { card ->
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
        val document = app.get("$mainUrl/vodsearch/-------------.html?wd=$query", headers = headers).document
        return document.select(".myui-vodlist li").mapNotNull { card ->
            val titleEl = card.selectFirst("h4 a, .detail a") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            val href = fixUrl(titleEl.attr("href"))
            val posterUrl = fixUrlNull(card.selectFirst("a.myui-vodlist__thumb img, .myui-vodlist__thumb")?.attr("data-original"))

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("No title found")
        val posterUrl = fixUrlNull(
            document.selectFirst(".myui-content__thumb img")?.attr("data-original")
                ?: document.selectFirst(".myui-content__thumb img")?.attr("src")
        )

        // Parse metadata
        val infoP = document.selectFirst(".myui-content__detail p.data")
            ?: document.selectFirst(".myui-content__detail")
        var year: Int? = null
        var tags: List<String>? = null

        document.select(".myui-content__detail p").forEach { p ->
            val text = p.text().trim()
            when {
                text.contains("分类：") -> {
                    val catLink = p.selectFirst("a")
                    if (catLink != null) tags = listOf(catLink.text().trim())
                }
                text.contains("年份：") -> {
                    val yearLink = p.select("a").lastOrNull { it.text().trim().matches(Regex("\\d{4}")) }
                    year = yearLink?.text()?.trim()?.toIntOrNull()
                }
            }
        }

        // Parse actors
        val actorP = document.select(".myui-content__detail p").find { it.text().contains("主演：") }
        val actors = actorP?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }

        // Parse plot
        val plot = document.selectFirst(".myui-content__detail p.desc, #desc .text")?.text()?.trim()
            ?: document.selectFirst(".col-pd p.desc")?.text()?.trim()

        // Parse all source tabs and their episodes
        val sourceTabs = document.select(".myui-panel_hd .nav-tabs a[href^=\"#playlist\"]")
        val allSourceEpisodes = mutableMapOf<String, List<Pair<String, String>>>() // sourceName -> [(label, href)]
        val sourceIndices = mutableListOf<Int>()

        sourceTabs.forEach { tab ->
            val playlistId = tab.attr("href").removePrefix("#")
            val tabName = tab.text().trim()
            val panel = document.getElementById(playlistId)
            val eps = panel?.select("a[href*=/vodplay/]")?.map { a ->
                a.text().trim() to a.attr("href")
            } ?: emptyList()
            if (eps.isNotEmpty()) {
                allSourceEpisodes[tabName] = eps
                // Extract source index from first episode href: /vodplay/{id}-{sourceIdx}-{epIdx}.html
                val match = Regex("/vodplay/\\d+-(\\d+)-\\d+\\.html").find(eps.first().second)
                match?.groupValues?.get(1)?.toIntOrNull()?.let { sourceIndices.add(it) }
            }
        }

        // Use the first source tab with the most episodes for the episode list
        val primarySource = allSourceEpisodes.maxByOrNull { it.value.size }
        val episodes = primarySource?.value?.mapIndexed { index, (label, href) ->
            // Extract vodId and episode index from href
            val m = Regex("/vodplay/(\\d+)-(\\d+)-(\\d+)\\.html").find(href)
            val vodId = m?.groupValues?.get(1) ?: ""
            val epIdx = m?.groupValues?.get(3) ?: "${index + 1}"
            // Store vodId, episode index, and all available source indices
            val data = "$vodId:::$epIdx:::${sourceIndices.joinToString(",")}"
            newEpisode(data) {
                this.name = label
                this.episode = index + 1
            }
        } ?: emptyList()

        // Determine type from breadcrumb
        val breadcrumb = document.select(".myui-page__breadcrumb a").map { it.text().trim() }
        val type = when {
            breadcrumb.any { it.contains("电影") || it.contains("片") } && episodes.size <= 1 -> TvType.Movie
            breadcrumb.any { it.contains("动漫") } -> TvType.Anime
            else -> TvType.TvSeries
        }

        // Parse recommendations
        val recommendations = document.select(".myui-vodlist li").mapNotNull { it.toSearchResult() }

        return if (type == TvType.Movie && episodes.size <= 1) {
            val dataUrl = if (episodes.isNotEmpty()) episodes.first().data else ""
            newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors?.map { ActorData(Actor(it)) }
            }
        } else {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors?.map { ActorData(Actor(it)) }
            }
        }
    }

    private val playerRegex = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;?\s*(?:</script>|$)""", RegexOption.DOT_MATCHES_ALL)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: "vodId:::epIdx:::sourceIdx1,sourceIdx2,..."
        val parts = data.split(":::")
        if (parts.size < 3) return false
        val vodId = parts[0]
        val epIdx = parts[1]
        val sourceIndices = parts[2].split(",").mapNotNull { it.toIntOrNull() }

        var found = false
        for (sid in sourceIndices) {
            try {
                val playUrl = "$mainUrl/vodplay/$vodId-$sid-$epIdx.html"
                val html = app.get(playUrl, headers = headers).text
                val match = playerRegex.find(html) ?: continue
                val jsonStr = match.groupValues[1]

                // Parse the JSON manually to extract url and from fields
                val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").find(jsonStr) ?: continue
                val videoUrl = urlMatch.groupValues[1].replace("\\/", "/")
                val fromMatch = Regex(""""from"\s*:\s*"([^"]+)"""").find(jsonStr)
                val fromSource = fromMatch?.groupValues?.get(1) ?: "unknown"

                // Get source name from the page
                val sourceNameMatch = Regex(""""nid"\s*:\s*(\d+)""").find(jsonStr)

                // Determine source display name
                val sourceName = when {
                    fromSource.contains("m3u8", ignoreCase = true) -> fromSource.uppercase()
                    fromSource == "qq" -> "TX线路"
                    fromSource.contains("jinyingyun", ignoreCase = true) -> "JY线路"
                    else -> fromSource.uppercase()
                }

                if (videoUrl.isBlank()) continue

                if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                    // Direct video URL
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name - $sourceName",
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                } else {
                    // Indirect URL (bilibili, youku, etc.) - try CloudStream3 extractors
                    val extracted = loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                    if (extracted) found = true
                }
            } catch (_: Exception) {
                continue
            }
        }
        return found
    }
}
