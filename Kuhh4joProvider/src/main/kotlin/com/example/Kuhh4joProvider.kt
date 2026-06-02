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
import java.security.MessageDigest

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

    private val apiBase = "$mainUrl/mw-movie"

    // Signing constants extracted from the site's JS bundle (chunk 2844)
    // signKey is from: signKey:"cb808529bae6b6be45ecfab29a4889bc" in the axios interceptor
    private val SIGN_KEY = "cb808529bae6b6be45ecfab29a4889bc"

    // deviceId is a UUID stored in localStorage; a fixed one works for anonymous calls
    private val deviceId = "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"

    override val mainPage = mainPageOf(
        "1" to "电影",
        "2" to "电视剧",
        "3" to "综艺",
        "4" to "动漫",
        "88" to "短剧",
    )

    // --- Signing (reverse-engineered from JS chunk 2844, module 49858) ---
    // Algorithm: sign = SHA1( MD5( sortedParams + "&key=" + signKey + "&t=" + timestamp ) )
    // where sortedParams = "k1=v1&k2=v2&..." sorted alphabetically by key

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun makeSignHeaders(params: Map<String, Any>): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val dataStr = params.entries.sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val h = if (dataStr.isNotEmpty()) "$dataStr&key=$SIGN_KEY&t=$timestamp"
                else "key=$SIGN_KEY&t=$timestamp"
        val sign = sha1(md5(h))
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
        @JsonProperty("vodYear") val vodYear: Int?,
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
        val type1 = request.data.toInt()
        val params = mapOf("pageNum" to page, "pageSize" to 20, "type1" to type1)
        Log.d("Kuhh4joProvider", "getMainPage type1=$type1 page=$page")
        val url = buildUrl("/anonymous/video/list", params)
        val data = app.get(url, headers = makeSignHeaders(params)).parsed<ListApiResponse>()
        val items = data.data?.list?.mapNotNull { toSearchResponse(it) } ?: emptyList()
        Log.d("Kuhh4joProvider", "getMainPage items=${items.size} total=${data.data?.totalCount}")
        return newHomePageResponse(
            list = HomePageList(request.name, items, false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val params = mapOf("keyword" to query, "pageNum" to 1, "pageSize" to 20)
        Log.d("Kuhh4joProvider", "search query=$query")
        val url = buildUrl("/anonymous/video/searchByWordPageable", params)
        val data = app.get(url, headers = makeSignHeaders(params)).parsed<ListApiResponse>()
        val items = data.data?.list?.mapNotNull { toSearchResponse(it) } ?: emptyList()
        Log.d("Kuhh4joProvider", "search results=${items.size}")
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val vodId = Regex("""/detail/(\d+)""").find(url)?.groupValues?.get(1)
            ?.toIntOrNull() ?: throw ErrorLoadingException("Invalid URL: $url")
        Log.d("Kuhh4joProvider", "load vodId=$vodId url=$url")

        val params = mapOf("id" to vodId)
        val apiUrl = buildUrl("/anonymous/video/detail", params)
        val resp = app.get(apiUrl, headers = makeSignHeaders(params)).parsed<DetailApiResponse>()
        val detail = resp.data ?: throw ErrorLoadingException("No detail for vodId=$vodId")

        val title = detail.vodName?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("No title for vodId=$vodId")
        val posterUrl = detail.vodPic
        val plot = (detail.vodContent ?: detail.vodBlurb)?.trim()?.takeIf { it.isNotEmpty() }
        val tags = detail.vodClass?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val actors = detail.vodActor?.split(",")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { ActorData(Actor(it)) }

        val episodes = detail.episodeList
            ?.sortedWith(compareBy({ it.sort ?: Int.MAX_VALUE }, { it.nid ?: Long.MAX_VALUE }))
            ?.mapIndexedNotNull { i, ep ->
                val nid = ep.nid ?: return@mapIndexedNotNull null
                val epName = ep.name?.trim()?.ifEmpty { null } ?: "第${i + 1}集"
                val epNum = epName.toIntOrNull() ?: (i + 1)
                newEpisode("$vodId:::$nid") {
                    this.name = epName
                    this.episode = epNum
                }
            } ?: emptyList()
        Log.d("Kuhh4joProvider", "load title=$title episodes=${episodes.size}")

        val tvType = typeId1ToTvType(detail.typeId1)

        return if (episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.actors = actors
                this.year = detail.vodYear
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.actors = actors
                this.year = detail.vodYear
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: "vodId:::nid"
        val parts = data.split(":::")
        if (parts.size < 2) {
            Log.w("Kuhh4joProvider", "loadLinks invalid data: $data")
            return false
        }
        val vodId = parts[0].toIntOrNull() ?: return false
        val nid = parts[1].toLongOrNull() ?: return false

        val params = mapOf("clientType" to 1, "id" to vodId, "nid" to nid)
        val url = buildUrl("/anonymous/v2/video/episode/url", params)
        Log.d("Kuhh4joProvider", "loadLinks vodId=$vodId nid=$nid url=$url")

        val resp = try {
            app.get(url, headers = makeSignHeaders(params)).parsed<PlayApiResponse>()
        } catch (e: Exception) {
            Log.e("Kuhh4joProvider", "loadLinks fetch failed: ${e.message}")
            return false
        }

        val plays = resp.data?.list
        Log.d("Kuhh4joProvider", "loadLinks plays=${plays?.size}")
        if (plays.isNullOrEmpty()) return false

        var found = false
        plays.forEach { play ->
            val m3u8 = play.url?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
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
            found = true
        }
        return found
    }
}
