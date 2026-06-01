package com.example

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

class IyftvProvider : MainAPI() {
    override var mainUrl = "https://www.iyf.tv"
    override var name = "IyfTV"
    override var lang = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/html, */*"
    )

    // Category IDs: 1=剧集, 2=电影, 3=综艺, 5=动漫
    override val mainPage = mainPageOf(
        "1" to "剧集",
        "2" to "电影",
        "3" to "综艺",
        "5" to "动漫",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ListResponse(
        @JsonProperty("data") val data: ListData?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ListData(
        @JsonProperty("list") val list: List<VodItem>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VodItem(
        @JsonProperty("id") val id: Any?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("pic") val pic: String?,
        @JsonProperty("img") val img: String?
    ) {
        fun getId() = id?.toString() ?: ""
        fun getTitle() = name ?: ""
        fun getPoster() = pic ?: img
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailResponse(
        @JsonProperty("data") val data: DetailData?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailData(
        @JsonProperty("info") val info: VodItem?,
        @JsonProperty("playList") val playList: List<PlaySource>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlaySource(
        @JsonProperty("name") val name: String?,
        @JsonProperty("list") val list: List<EpisodeItem>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("id") val id: Any?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayInfoResponse(
        @JsonProperty("data") val data: PlayInfoData?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayInfoData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("encrypt") val encrypt: Int?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = try {
            app.get(
                "$mainUrl/api/getMovieList?typeId=${request.data}&page=$page&size=20",
                headers = headers
            ).parsed<ListResponse>()
        } catch (_: Exception) {
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }
        val items = response.data?.list?.mapNotNull { item ->
            val id = item.getId().ifEmpty { return@mapNotNull null }
            val title = item.getTitle().ifEmpty { return@mapNotNull null }
            newMovieSearchResponse(title, "$mainUrl/fydetail?id=$id", TvType.Movie) {
                this.posterUrl = item.getPoster()
            }
        } ?: emptyList()
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = try {
            app.get("$mainUrl/api/search?wd=$query&page=1", headers = headers).parsed<ListResponse>()
        } catch (_: Exception) {
            return emptyList()
        }
        return response.data?.list?.mapNotNull { item ->
            val id = item.getId().ifEmpty { return@mapNotNull null }
            val title = item.getTitle().ifEmpty { return@mapNotNull null }
            newMovieSearchResponse(title, "$mainUrl/fydetail?id=$id", TvType.Movie) {
                this.posterUrl = item.getPoster()
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val vodId = Regex("[?&]id=([^&]+)").find(url)?.groupValues?.get(1)
            ?: Regex("/(\\d+)(?:[/?]|\$)").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Cannot extract VOD ID from URL: $url")

        val detail = try {
            app.get("$mainUrl/api/getMovieDetail?id=$vodId", headers = headers).parsed<DetailResponse>()
        } catch (_: Exception) {
            throw ErrorLoadingException("Failed to load detail for ID: $vodId")
        }

        val info = detail.data?.info ?: throw ErrorLoadingException("No info for ID: $vodId")
        val title = info.getTitle().ifEmpty { throw ErrorLoadingException("No title for ID: $vodId") }
        val posterUrl = info.getPoster()

        // Pick the source with the most episodes
        val source = detail.data.playList?.maxByOrNull { it.list?.size ?: 0 }
        val episodes = source?.list?.mapIndexed { index, ep ->
            val epId = ep.id?.toString() ?: "${index + 1}"
            val epName = ep.name ?: "第${index + 1}集"
            newEpisode("$vodId:::$epId") {
                this.name = epName
                this.episode = index + 1
            }
        } ?: emptyList()

        return if (episodes.size <= 1) {
            val dataStr = episodes.firstOrNull()?.data ?: "$vodId:::1"
            newMovieLoadResponse(title, url, TvType.Movie, dataStr) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data: "vodId:::epId"
        val parts = data.split(":::")
        val vodId = parts[0]
        val epId = parts.getOrNull(1) ?: "1"

        // Intercept the same API the browser calls to resolve the video URL
        val playInfo = try {
            app.get(
                "$mainUrl/api/getPlayInfo?id=$vodId&nid=$epId",
                headers = headers
            ).parsed<PlayInfoResponse>()
        } catch (_: Exception) {
            return false
        }

        var url = playInfo.data?.url ?: return false
        if (url.isBlank()) return false

        val encrypt = playInfo.data.encrypt ?: 0
        url = when (encrypt) {
            1 -> try { String(Base64.decode(url, Base64.DEFAULT)) } catch (_: Exception) { url }
            2 -> try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
            else -> url
        }
        if (url.isBlank()) return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
