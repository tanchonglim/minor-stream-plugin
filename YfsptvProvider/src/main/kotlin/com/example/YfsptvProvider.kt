package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class YfsptvProvider : MainAPI() {
    override var mainUrl   = "https://www.yfsp.tv"
    override var name      = "爱壹帆"
    override var lang      = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiBase    = "https://m10.yfsp.tv"
    private val rankBase   = "https://rankv21.yfsp.tv"
    private val uploadBase = "https://upload.yfsp.tv"

    companion object {
        // Fallback keys extracted from yfsp.tv/list (refreshed automatically on first call)
        internal const val FALLBACK_PUB_KEY  = "CJSuC3GmCp4uDoutDJ9VKqTVCZ0tBZGsBZ8oD2uuD5yQc1ko6pARcXaOCJCQ6x4Pd9cP71gochAQc9oPcZ8QcQzCJKmC3TZEJXaOJbYDZ8rCZCqE3GuDZGpC38sDp0sOM3"
        internal const val FALLBACK_PRIV_KEY = "SuC3JSuC3GmCp4uDoutD"

        @Volatile private var cachedPub:  String? = null
        @Volatile private var cachedPriv: String? = null
    }

    override val mainPage = mainPageOf(
        "0,1,3" to "电影",
        "0,1,4" to "电视剧",
        "0,1,5" to "综艺",
        "0,1,6" to "动漫",
        "0,1,8" to "短剧",
    )

    // ── pConfig ─────────────────────────────────────────────────────────────

    /** Fetches publicKey + privateKey[0] from the HTML page's inline pConfig JSON. */
    private suspend fun getPConfig(): Pair<String, String> {
        cachedPub?.let { pub -> cachedPriv?.let { priv -> return pub to priv } }
        return try {
            val html = app.get("$mainUrl/list").text
            val pub  = Regex(""""publicKey"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val priv = Regex(""""privateKey"\s*:\s*\["([^"]+)"\]""").find(html)?.groupValues?.get(1)
            if (pub != null && priv != null) {
                Log.d("YfsptvProvider", "pConfig fetched pub=${pub.take(20)}… priv=${priv.take(10)}…")
                cachedPub = pub; cachedPriv = priv
                pub to priv
            } else {
                Log.w("YfsptvProvider", "pConfig not found, using fallback")
                FALLBACK_PUB_KEY to FALLBACK_PRIV_KEY
            }
        } catch (e: Exception) {
            Log.w("YfsptvProvider", "pConfig fetch failed: ${e.message}, using fallback")
            FALLBACK_PUB_KEY to FALLBACK_PRIV_KEY
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun atypeToTvType(atypeName: String?, videoClassID: String?): TvType = when {
        atypeName == "电影"  -> TvType.Movie
        atypeName == "动漫"  -> TvType.Anime
        videoClassID?.startsWith("0,1,3") == true -> TvType.Movie
        videoClassID?.startsWith("0,1,6") == true -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun detailUrl(key: String) = "$mainUrl/$key"

    // ── Main page ────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (pub, priv) = getPConfig()
        val params = mapOf(
            "page"   to "$page",
            "cid"    to request.data,
            "size"   to "20",
            "isn"    to "0",
            "isfree" to "-1",
            "cinema" to "1",
        )
        val url = buildYfsUrl("$apiBase/api/list/index", params, pub, priv)
        Log.d("YfsptvProvider", "getMainPage url=$url")
        val resp = app.get(url).parsed<YfspApiResponse<YfspListItem>>()
        val items = resp.data?.info?.mapNotNull { listItemToSearch(it) } ?: emptyList()
        Log.d("YfsptvProvider", "getMainPage items=${items.size}")
        return newHomePageResponse(HomePageList(request.name, items, false), hasNext = items.isNotEmpty())
    }

    private fun listItemToSearch(item: YfspListItem): SearchResponse? {
        val key   = item.key?.takeIf { it.isNotEmpty() } ?: return null
        val title = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tvType = atypeToTvType(item.atypeName, item.videoClassID)
        return newMovieSearchResponse(title, detailUrl(key), tvType) {
            this.posterUrl = item.image
        }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val (pub, priv) = getPConfig()
        val rawParams = mapOf("cinema" to "1", "tags" to query, "size" to "20", "page" to "1", "orderby" to "4")
        val urlParams = rawParams + mapOf("tags" to URLEncoder.encode(query, "UTF-8"))
        val url = buildYfsUrl("$rankBase/v3/list/briefsearch", rawParams, pub, priv, urlParams)
        Log.d("YfsptvProvider", "search url=$url")
        val resp = app.get(url).parsed<YfspApiResponse<YfspBriefSearchInfo>>()
        val results = resp.data?.info?.firstOrNull()?.result ?: emptyList()
        Log.d("YfsptvProvider", "search results=${results.size}")
        return results.mapNotNull { searchResultToSearch(it) }
    }

    private fun searchResultToSearch(item: YfspSearchResult): SearchResponse? {
        val key   = item.contxt?.takeIf { it.isNotEmpty() } ?: return null
        val title = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tvType = atypeToTvType(item.atypeName, null)
        return newMovieSearchResponse(title, detailUrl(key), tvType) {
            this.posterUrl = item.imgPath
        }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val key = url.substringAfterLast("/")
        Log.d("YfsptvProvider", "load key=$key url=$url")
        val (pub, priv) = getPConfig()

        // Detail
        val detailParams = mapOf(
            "ispath" to "false", "cinema" to "1", "device" to "1",
            "player" to "CkPlayer", "tech" to "HLS", "country" to "HU",
            "lang" to "cns", "v" to "1", "id" to key, "region" to "GL.",
        )
        val detailUrl = buildYfsUrl("$apiBase/v3/video/detail", detailParams, pub, priv)
        Log.d("YfsptvProvider", "load detailUrl=$detailUrl")
        val detailResp = app.get(detailUrl).parsed<YfspApiResponse<YfspDetail>>()
        val detail = detailResp.data?.info?.firstOrNull()
            ?: throw ErrorLoadingException("No detail for key=$key")

        val title     = detail.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("No title for key=$key")
        val posterUrl = detail.imgPath
        val plot      = detail.contxt?.trim()?.takeIf { it.isNotEmpty() }
        val actors    = detail.stars?.filter { it.isNotEmpty() }?.map { ActorData(Actor(it)) }
        val tags      = listOfNotNull(detail.language, detail.regional).filter { it.isNotEmpty() }
        val year      = detail.postYear

        Log.d("YfsptvProvider", "load title=$title isFilm=${detail.isFilm} isSerial=${detail.isSerial} serialCount=${detail.serialCount}")

        // Play endpoint → get episode IDs
        val playParams = mapOf(
            "cinema" to "1", "id" to key, "lang" to "cns", "usersign" to "1",
            "region" to "GL.", "device" to "1", "a" to "1", "isMasterSupport" to "1",
        )
        val playUrl = buildYfsUrl("$apiBase/v3/video/play", playParams, pub, priv)
        Log.d("YfsptvProvider", "load playUrl=$playUrl")
        val playResp = runCatching { app.get(playUrl).parsed<YfspApiResponse<YfspPlayInfo>>() }.getOrNull()
        val playInfo = playResp?.data?.info?.firstOrNull()

        val ep1MediaKey = playInfo?.mediaKey ?: ""
        val ep1UniqueId = playInfo?.clarity?.firstOrNull { it.isVIP == false && it.isEnabled == true }?.uniqueID
            ?: playInfo?.clarity?.firstOrNull { it.isEnabled == true }?.uniqueID
        val ep1Title   = playInfo?.mediaTitle ?: "01"
        Log.d("YfsptvProvider", "load ep1 mediaKey=$ep1MediaKey uniqueId=$ep1UniqueId title=$ep1Title")

        val isMovie = detail.isFilm == true || (detail.isSerial == false && (detail.serialCount ?: 1) <= 1)

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, ep1UniqueId?.toString() ?: "") {
                this.posterUrl = posterUrl
                this.plot      = plot
                this.actors    = actors
                this.tags      = tags
                this.year      = year
            }
        }

        // Series → build episode list via getnextvideo chain
        val episodes = mutableListOf<Episode>()
        if (ep1UniqueId != null) {
            episodes += newEpisode(ep1UniqueId.toString()) {
                name    = ep1Title
                episode = ep1Title.toIntOrNull() ?: 1
            }
        }

        var curKey = ep1MediaKey
        val maxEps = (detail.serialCount ?: 100).coerceAtMost(200)
        Log.d("YfsptvProvider", "load chaining getnextvideo from ep1, maxEps=$maxEps")

        while (curKey.isNotEmpty() && episodes.size < maxEps) {
            val nextParams = mapOf("cinema" to "1", "id" to curKey)
            val nextUrl    = buildYfsUrl("$apiBase/v3/video/getnextvideo", nextParams, pub, priv)
            val nextEp     = runCatching {
                app.get(nextUrl).parsed<YfspApiResponse<YfspNextEp>>().data?.info?.firstOrNull()
            }.getOrNull() ?: break

            val epId    = nextEp.id ?: break
            val epTitle = nextEp.title ?: "${episodes.size + 1}"
            episodes += newEpisode(epId.toString()) {
                name    = epTitle
                episode = epTitle.toIntOrNull() ?: (episodes.size + 1)
            }
            curKey = nextEp.key ?: ""
            Log.d("YfsptvProvider", "load got ep${episodes.size} title=$epTitle id=$epId key=$curKey")
        }

        Log.d("YfsptvProvider", "load series eps=${episodes.size}")
        val tvType = atypeToTvType(null, detail.key?.let { "0,1,4" })
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = posterUrl
            this.plot      = plot
            this.actors    = actors
            this.tags      = tags
            this.year      = year
        }
    }

    // ── Load links ───────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("YfsptvProvider", "loadLinks data=$data")
        val episodeId = data.toLongOrNull() ?: run {
            Log.w("YfsptvProvider", "loadLinks: data is not a numeric id")
            return false
        }

        // MasterPlayList returns a valid M3U8 master playlist — no signing required.
        val masterUrl = "$uploadBase/api/video/MasterPlayList?id=$episodeId"
        Log.d("YfsptvProvider", "loadLinks masterUrl=$masterUrl")

        val m3u8Body = runCatching { app.get(masterUrl).text }.getOrNull() ?: run {
            Log.w("YfsptvProvider", "loadLinks: failed to fetch MasterPlayList")
            return false
        }

        // Extract the first HLS chunklist URL from the M3U8
        val hlsUrl = m3u8Body.lines()
            .firstOrNull { it.startsWith("http") && (it.contains(".m3u8") || it.contains("chunklist")) }
            ?: run {
                Log.w("YfsptvProvider", "loadLinks: no HLS url in MasterPlayList body")
                return false
            }

        Log.d("YfsptvProvider", "loadLinks hlsUrl=${hlsUrl.take(80)}")
        callback.invoke(
            newExtractorLink(
                source = name,
                name   = name,
                url    = hlsUrl,
                type   = ExtractorLinkType.M3U8,
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
