package com.example

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class Kuhh4joProvider : MainAPI() {
    override var mainUrl = "https://www.kuhh4jo.com"
    override var name = "爱电影"
    override var lang = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val apiBase = "$mainUrl/api/mw-movie"

    // Fallback sign key extracted from JS bundle (chunk 5055, axios interceptor).
    // getSignKey() tries to fetch the live value on first use.
    companion object {
        internal const val FALLBACK_SIGN_KEY = "cb808529bae6b6be45ecfab29a4889bc"
        @Volatile private var cachedSignKey: String? = null
    }

    // Fixed UUID — arbitrary for anonymous API access (mirrors localStorage value)
    private val deviceId = "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"

    override val mainPage = mainPageOf(
        "1" to "电影",
        "2" to "电视剧",
        "3" to "综艺",
        "4" to "动漫",
        "88" to "短剧",
    )

    // Signing utilities (md5, sha1, buildSign) live in SigningUtils.kt — pure JVM, no Android deps.

    // Dynamically fetch the signing key from the site's JS bundle.
    // Falls back to FALLBACK_SIGN_KEY on any error.
    private suspend fun getSignKey(): String {
        cachedSignKey?.let {
            Log.d("Kuhh4joProvider", "getSignKey cache hit: $it")
            return it
        }
        Log.d("Kuhh4joProvider", "getSignKey cache miss, fetching from JS bundle")
        return try {
            val html = app.get(mainUrl).text
            val chunkUrls = Regex("""/_next/static/chunks/[^\s"'<>]+\.js""")
                .findAll(html)
                .map { "$mainUrl${it.value}" }
                .distinct()
                .toList()
            Log.d("Kuhh4joProvider", "getSignKey scanning ${chunkUrls.size} chunks")
            var result = FALLBACK_SIGN_KEY
            for (url in chunkUrls) {
                val js = runCatching { app.get(url).text }.getOrNull() ?: continue
                val key = Regex("""signKey\s*:\s*"([0-9a-f]{32})"""")
                    .find(js)?.groupValues?.get(1)
                if (key != null) {
                    Log.d("Kuhh4joProvider", "getSignKey found key=$key in $url")
                    result = key
                    break
                }
            }
            if (result == FALLBACK_SIGN_KEY) {
                Log.w("Kuhh4joProvider", "getSignKey not found in any chunk, using fallback=$result")
            }
            result.also { cachedSignKey = it }
        } catch (e: Exception) {
            Log.w("Kuhh4joProvider", "getSignKey exception, using fallback: ${e.message}")
            FALLBACK_SIGN_KEY
        }
    }

    private fun makeSignHeaders(params: Map<String, Any>, signKey: String): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val sign = buildSign(params, signKey, timestamp)
        Log.d("Kuhh4joProvider", "makeSignHeaders sign=$sign t=$timestamp")
        return mapOf(
            "sign" to sign,
            "t" to timestamp.toString(),
            "deviceId" to deviceId,
            "authorization" to "",
            "client-type" to "1",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to mainUrl
        )
    }

    private fun buildUrl(path: String, params: Map<String, Any>): String {
        val qs = params.entries.sortedBy { it.key }
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value.toString(), "UTF-8")}" }
        return "$apiBase$path?$qs"
    }

    // --- Data classes ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoItem(
        @JsonProperty("vodId") val vodId: Int?,
        @JsonProperty("vodName") val vodName: String?,
        @JsonProperty("vodPic") val vodPic: String?,
        @JsonProperty("typeId1") val typeId1: Int?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ListData(
        @JsonProperty("list") val list: List<VideoItem>?,
        @JsonProperty("totalCount") val totalCount: Int?,
        @JsonProperty("totalPage") val totalPage: Int?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ListApiResponse(
        @JsonProperty("code") val code: Int?,
        @JsonProperty("data") val data: ListData?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        @JsonProperty("nid") val nid: Long?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("sort") val sort: Int?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoDetail(
        @JsonProperty("vodId") val vodId: Int?,
        @JsonProperty("vodName") val vodName: String?,
        @JsonProperty("vodPic") val vodPic: String?,
        @JsonProperty("vodContent") val vodContent: String?,
        @JsonProperty("vodBlurb") val vodBlurb: String?,
        @JsonProperty("vodActor") val vodActor: String?,
        @JsonProperty("vodDirector") val vodDirector: String?,
        @JsonProperty("vodClass") val vodClass: String?,
        @JsonProperty("vodArea") val vodArea: String?,
        // API returns String (e.g. "" or "2024"), not Int
        @JsonProperty("vodYear") val vodYear: String?,
        @JsonProperty("typeId1") val typeId1: Int?,
        @JsonProperty("episodeList") val episodeList: List<EpisodeItem>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailApiResponse(
        @JsonProperty("code") val code: Int?,
        @JsonProperty("data") val data: VideoDetail?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayItem(
        @JsonProperty("url") val url: String?,
        @JsonProperty("resolutionName") val resolutionName: String?,
        @JsonProperty("resolution") val resolution: Int?,
        @JsonProperty("needLogin") val needLogin: Boolean?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayData(
        @JsonProperty("list") val list: List<PlayItem>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayApiResponse(
        @JsonProperty("code") val code: Int?,
        @JsonProperty("data") val data: PlayData?
    )

    // --- Helpers ---

    private fun typeId1ToTvType(typeId1: Int?): TvType = when (typeId1) {
        1 -> TvType.Movie
        4 -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun resolutionToQuality(resolution: Int?): Int = when (resolution) {
        1080 -> Qualities.P1080.value
        720  -> Qualities.P720.value
        480  -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun toSearchResponse(item: VideoItem): SearchResponse? {
        val id = item.vodId ?: return null
        val title = item.vodName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return newMovieSearchResponse(title, "$mainUrl/detail/$id", typeId1ToTvType(item.typeId1)) {
            this.posterUrl = item.vodPic
        }
    }

    // --- MainAPI overrides ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val signKey = getSignKey()
        val type1 = request.data.toInt()
        val params = mapOf("pageNum" to page, "pageSize" to 20, "type1" to type1)
        Log.d("Kuhh4joProvider", "getMainPage type1=$type1 page=$page")
        val url = buildUrl("/anonymous/video/list", params)
        val data = app.get(url, headers = makeSignHeaders(params, signKey)).parsed<ListApiResponse>()
        val items = data.data?.list?.mapNotNull { toSearchResponse(it) } ?: emptyList()
        Log.d("Kuhh4joProvider", "getMainPage items=${items.size} total=${data.data?.totalCount}")
        return newHomePageResponse(
            list = HomePageList(request.name, items, false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val signKey = getSignKey()
        val params = mapOf("keyword" to query, "pageNum" to 1, "pageSize" to 20)
        Log.d("Kuhh4joProvider", "search query=$query")
        val url = buildUrl("/anonymous/video/searchByWordPageable", params)
        val data = app.get(url, headers = makeSignHeaders(params, signKey)).parsed<ListApiResponse>()
        val items = data.data?.list?.mapNotNull { toSearchResponse(it) } ?: emptyList()
        Log.d("Kuhh4joProvider", "search results=${items.size}")
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val vodId = Regex("""/detail/(\d+)""").find(url)?.groupValues?.get(1)
            ?.toIntOrNull() ?: throw ErrorLoadingException("Invalid URL: $url")
        Log.d("Kuhh4joProvider", "load START vodId=$vodId url=$url")

        val signKey = getSignKey()
        val params = mapOf("id" to vodId)
        val apiUrl = buildUrl("/anonymous/video/detail", params)
        Log.d("Kuhh4joProvider", "load detailApiUrl=$apiUrl")

        val rawDetail = app.get(apiUrl, headers = makeSignHeaders(params, signKey))
        Log.d("Kuhh4joProvider", "load detail httpCode=${rawDetail.code} bodyLen=${rawDetail.text.length}")
        Log.d("Kuhh4joProvider", "load detail body=${rawDetail.text.take(300)}")

        val resp = rawDetail.parsed<DetailApiResponse>()
        Log.d("Kuhh4joProvider", "load parsed code=${resp.code} hasData=${resp.data != null}")
        val detail = resp.data ?: throw ErrorLoadingException("No detail for vodId=$vodId")

        val title = detail.vodName?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("No title for vodId=$vodId")
        val posterUrl = detail.vodPic
        val plot = (detail.vodContent ?: detail.vodBlurb)?.trim()?.takeIf { it.isNotEmpty() }
        val tags = detail.vodClass?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val actors = detail.vodActor?.split(",")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { ActorData(Actor(it)) }
        val year = detail.vodYear?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        Log.d("Kuhh4joProvider", "load title=$title typeId1=${detail.typeId1} year=$year episodeListSize=${detail.episodeList?.size}")

        val episodes = detail.episodeList
            ?.sortedWith(compareBy({ it.sort ?: Int.MAX_VALUE }, { it.nid ?: Long.MAX_VALUE }))
            ?.mapIndexedNotNull { i, ep ->
                val nid = ep.nid ?: run {
                    Log.w("Kuhh4joProvider", "load ep[$i] has null nid, skipping")
                    return@mapIndexedNotNull null
                }
                val epName = ep.name?.trim()?.ifEmpty { null } ?: "第${i + 1}集"
                val epNum = epName.toIntOrNull() ?: (i + 1)
                val epData = "$vodId:::$nid"
                Log.d("Kuhh4joProvider", "load ep[$i] name=$epName nid=$nid data=$epData")
                newEpisode(epData) {
                    this.name = epName
                    this.episode = epNum
                }
            } ?: emptyList()

        val tvType = typeId1ToTvType(detail.typeId1)
        Log.d("Kuhh4joProvider", "load episodes=${episodes.size} tvType=$tvType isMovie=${episodes.size <= 1}")

        return if (episodes.size <= 1) {
            val movieData = episodes.firstOrNull()?.data ?: ""
            Log.d("Kuhh4joProvider", "load -> MovieLoadResponse dataUrl=$movieData")
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.actors = actors
                this.year = year
            }
        } else {
            Log.d("Kuhh4joProvider", "load -> TvSeriesLoadResponse ${episodes.size} episodes")
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.actors = actors
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Kuhh4joProvider", "loadLinks START data=$data isCasting=$isCasting")

        // data format: "vodId:::nid"
        val parts = data.split(":::")
        Log.d("Kuhh4joProvider", "loadLinks parts=${parts.size} -> ${parts}")
        if (parts.size < 2) {
            Log.w("Kuhh4joProvider", "loadLinks FAIL: data has no ':::' separator")
            return false
        }
        val vodId = parts[0].toIntOrNull()
            ?: Regex("""(\d+)\s*$""").find(parts[0])?.groupValues?.get(1)?.toIntOrNull()
            ?: run {
                Log.w("Kuhh4joProvider", "loadLinks FAIL: parts[0]='${parts[0]}' not an Int or URL with trailing ID")
                return false
            }
        val nid = parts[1].toLongOrNull() ?: run {
            Log.w("Kuhh4joProvider", "loadLinks FAIL: parts[1]='${parts[1]}' not a Long")
            return false
        }
        Log.d("Kuhh4joProvider", "loadLinks parsed vodId=$vodId nid=$nid")

        val signKey = getSignKey()
        Log.d("Kuhh4joProvider", "loadLinks using signKey=$signKey")

        val params = mapOf("clientType" to 1, "id" to vodId, "nid" to nid)
        val url = buildUrl("/anonymous/v2/video/episode/url", params)
        Log.d("Kuhh4joProvider", "loadLinks requestUrl=$url")

        val rawResp = try {
            app.get(url, headers = makeSignHeaders(params, signKey))
        } catch (e: Exception) {
            Log.e("Kuhh4joProvider", "loadLinks FAIL: HTTP request threw: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }

        Log.d("Kuhh4joProvider", "loadLinks httpStatusCode=${rawResp.code}")
        Log.d("Kuhh4joProvider", "loadLinks rawBody=${rawResp.text.take(800)}")

        val resp = try {
            rawResp.parsed<PlayApiResponse>()
        } catch (e: Exception) {
            Log.e("Kuhh4joProvider", "loadLinks FAIL: JSON parse threw: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }

        Log.d("Kuhh4joProvider", "loadLinks parsed apiCode=${resp.code} hasData=${resp.data != null} listSize=${resp.data?.list?.size}")
        if (resp.code != null && resp.code != 200) {
            Log.w("Kuhh4joProvider", "loadLinks FAIL: API error code=${resp.code}")
            cachedSignKey = null
            return false
        }

        val plays = resp.data?.list
        if (plays.isNullOrEmpty()) {
            Log.w("Kuhh4joProvider", "loadLinks FAIL: play list is null or empty")
            return false
        }

        Log.d("Kuhh4joProvider", "loadLinks got ${plays.size} play items")
        var found = false
        plays.forEachIndexed { i, play ->
            Log.d("Kuhh4joProvider", "loadLinks play[$i] url=${play.url?.take(80)} res=${play.resolution} needLogin=${play.needLogin}")
            val m3u8 = play.url?.trim()?.takeIf { it.isNotBlank() } ?: run {
                Log.w("Kuhh4joProvider", "loadLinks play[$i] has blank url, skipping")
                return@forEachIndexed
            }
            val qualityName = play.resolutionName ?: "线路"
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - $qualityName",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = resolutionToQuality(play.resolution)
                }
            )
            Log.d("Kuhh4joProvider", "loadLinks play[$i] callback invoked for $qualityName")
            found = true
        }
        Log.d("Kuhh4joProvider", "loadLinks END found=$found")
        return found
    }
}
