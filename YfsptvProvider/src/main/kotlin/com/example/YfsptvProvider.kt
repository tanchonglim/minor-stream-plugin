package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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

    // Shared Cloudflare bypass interceptor — caches cf_clearance cookies across requests
    private val cfKiller = CloudflareKiller()

    companion object {
        private const val TAG = "YfsptvProvider"

        // Fallback keys from pConfig when the live page can't be fetched.
        // pConfig.publicKey and pConfig.privateKey[0] from www.yfsp.tv/list as of 2026-06.
        internal const val FALLBACK_PUB_KEY  = "CJSuC3GnC30uC2utEJDVKqTVCZ0tBZGsBZ8oD2uuD5yQcneRcvcS6XkRihCQ61aSd9op6Z8SCZAmcviSchCR62zEJ4tCMOoC6CtEMCpDMLXDMCqE3PcC68mC38tP6KuDZ5"
        internal const val FALLBACK_PRIV_KEY = "SuC3JSuC3GnC30uC2utE"

        @Volatile private var cachedKeys: Pair<String, String>? = null
    }

    override val mainPage = mainPageOf(
        "0,1,3" to "电影",
        "0,1,4" to "电视剧",
        "0,1,5" to "综艺",
        "0,1,6" to "动漫",
        "0,1,8" to "短剧",
    )

    // ── pConfig ─────────────────────────────────────────────────────────────

    /**
     * Derives privKey from pubKey: "SuC3JSuC3G" + pub[7..16].
     * pub[7] varies per key rotation (e.g. 'm', 'n') — it must not be hardcoded.
     * Used as fallback when pConfig.privateKey is absent from the HTML.
     */
    private fun derivePrivKey(pub: String): String = "SuC3JSuC3G" + pub.substring(7, 17)

    /**
     * Returns (publicKey, privateKey) from the pConfig block in www.yfsp.tv/list.
     * The HTML embeds two publicKey fields: authConfig.publicKey (Cloudflare) comes first,
     * then pConfig.publicKey (API signing key). We scope the regex to the pConfig section.
     * privateKey is served directly in pConfig.privateKey[0]; derivation is used as fallback.
     */
    private suspend fun getPConfig(): Pair<String, String> {
        Log.d(TAG, "getPConfig START cacheHit=${cachedKeys != null}")
        cachedKeys?.let { keys ->
            Log.d(TAG, "getPConfig CACHE HIT pub=${keys.first.take(20)}…")
            return keys
        }
        return try {
            Log.d(TAG, "getPConfig fetching $mainUrl/list")
            val html = app.get("$mainUrl/list", interceptor = cfKiller).text
            Log.d(TAG, "getPConfig html length=${html.length}")
            // Scope extraction to the pConfig section — authConfig.publicKey appears first and must be skipped.
            val pConfigSection = html.substringAfter("\"pConfig\"", "")
            val pub  = Regex(""""publicKey"\s*:\s*"([^"]+)"""").find(pConfigSection)?.groupValues?.get(1)
            val priv = Regex(""""privateKey"\s*:\s*\["([^"]+)"""").find(pConfigSection)?.groupValues?.get(1)
            if (pub != null && pub.length >= 17) {
                val effectivePriv = priv ?: derivePrivKey(pub)
                Log.d(TAG, "getPConfig OK pub=${pub.take(20)}… priv=${effectivePriv.take(10)}… (privFromHtml=${priv != null})")
                val keys = pub to effectivePriv
                cachedKeys = keys
                keys
            } else {
                Log.w(TAG, "getPConfig pConfig.publicKey not found or too short (pub=${pub?.take(20)}), using fallback")
                FALLBACK_PUB_KEY to FALLBACK_PRIV_KEY
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPConfig EXCEPTION ${e.javaClass.simpleName}: ${e.message}, using fallback")
            FALLBACK_PUB_KEY to FALLBACK_PRIV_KEY
        }
    }

    /** Clears the cached keys, forcing a fresh fetch on the next getPConfig call. */
    private fun invalidateKeyCache() {
        Log.d(TAG, "invalidateKeyCache: clearing cachedKeys")
        cachedKeys = null
    }

    /**
     * Wraps app.get() with response diagnostics.
     * Logs HTTP status, CF-Ray header, and a body preview so Cloudflare blocks
     * are immediately visible in logcat even when JSON parsing succeeds on bad data.
     * Uses .also{} so the return type is inferred from app.get() and parsed<T>() stays available.
     */
    private suspend fun apiGet(url: String) = app.get(url).also { r ->
        val status = r.code
        val cfRay  = r.headers["CF-Ray"]
        val body   = r.text   // NiceResponse caches this; parsed<T>() reuses the cached string
        val isHtml = body.trimStart().startsWith("<")
        val hasCf  = cfRay != null || body.contains("cloudflare", ignoreCase = true)
        if (status != 200 || isHtml || hasCf) {
            Log.w(TAG, "apiGet CF/BLOCK? status=$status cfRay=$cfRay isHtml=$isHtml url=${url.take(80)} body=${body.take(200)}")
        } else {
            Log.d(TAG, "apiGet OK status=$status cfRay=$cfRay url=${url.take(80)} body=${body.take(80)}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun atypeToTvType(atypeName: String?, videoClassID: String?): TvType {
        val result = when {
            atypeName == "电影"  -> TvType.Movie
            atypeName == "动漫"  -> TvType.Anime
            videoClassID?.startsWith("0,1,3") == true -> TvType.Movie
            videoClassID?.startsWith("0,1,6") == true -> TvType.Anime
            else -> TvType.TvSeries
        }
        Log.d(TAG, "atypeToTvType atypeName=$atypeName videoClassID=$videoClassID -> $result")
        return result
    }

    private fun detailUrl(key: String): String {
        val url = "$mainUrl/$key"
        Log.d(TAG, "detailUrl key=$key -> $url")
        return url
    }

    // ── Main page ────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage START page=$page category='${request.name}' cid=${request.data}")
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
        Log.d(TAG, "getMainPage requestUrl=$url")
        var resp = apiGet(url).parsed<YfspApiResponse<YfspListItem>>()
        if (resp.data?.code == 1) {
            // Signature rejected — cached key stale; retry once with fresh key.
            Log.w(TAG, "getMainPage code=1 on first attempt; invalidating key cache and retrying")
            invalidateKeyCache()
            val (pub2, priv2) = getPConfig()
            val url2 = buildYfsUrl("$apiBase/api/list/index", params, pub2, priv2)
            resp = apiGet(url2).parsed()
        }
        Log.d(TAG, "getMainPage apiCode=${resp.data?.code} rawInfoSize=${resp.data?.info?.size}")
        val items = resp.data?.info?.mapNotNull { listItemToSearch(it) } ?: emptyList()
        Log.d(TAG, "getMainPage END page=$page items=${items.size} hasNext=${items.isNotEmpty()}")
        return newHomePageResponse(HomePageList(request.name, items, false), hasNext = items.isNotEmpty())
    }

    private fun listItemToSearch(item: YfspListItem): SearchResponse? {
        Log.d(TAG, "listItemToSearch key=${item.key} title=${item.title} atype=${item.atypeName}")
        val key = item.key?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "listItemToSearch SKIP: blank key for title=${item.title}")
            return null
        }
        val title = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "listItemToSearch SKIP: blank title for key=$key")
            return null
        }
        val tvType = atypeToTvType(item.atypeName, item.videoClassID)
        Log.d(TAG, "listItemToSearch OK key=$key title=$title tvType=$tvType poster=${item.image?.take(40)}")
        return newMovieSearchResponse(title, detailUrl(key), tvType) {
            this.posterUrl = item.image
        }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search START query='$query'")
        val (pub, priv) = getPConfig()
        val rawParams = mapOf("cinema" to "1", "tags" to query, "size" to "20", "page" to "1", "orderby" to "4")
        val urlParams = rawParams + mapOf("tags" to URLEncoder.encode(query, "UTF-8"))
        val url = buildYfsUrl("$rankBase/v3/list/briefsearch", rawParams, pub, priv, urlParams)
        Log.d(TAG, "search requestUrl=$url")
        val resp = apiGet(url).parsed<YfspApiResponse<YfspBriefSearchInfo>>()
        Log.d(TAG, "search apiCode=${resp.data?.code} briefInfoSize=${resp.data?.info?.size}")
        val results = resp.data?.info?.firstOrNull()?.result ?: emptyList()
        Log.d(TAG, "search rawResults=${results.size}")
        val mapped = results.mapNotNull { searchResultToSearch(it) }
        Log.d(TAG, "search END query='$query' mappedResults=${mapped.size}")
        return mapped
    }

    private fun searchResultToSearch(item: YfspSearchResult): SearchResponse? {
        Log.d(TAG, "searchResultToSearch contxt=${item.contxt?.take(15)} title=${item.title} atype=${item.atypeName}")
        val key = item.contxt?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "searchResultToSearch SKIP: blank contxt for title=${item.title}")
            return null
        }
        val title = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "searchResultToSearch SKIP: blank title for key=$key")
            return null
        }
        val tvType = atypeToTvType(item.atypeName, null)
        Log.d(TAG, "searchResultToSearch OK key=$key title=$title tvType=$tvType")
        return newMovieSearchResponse(title, detailUrl(key), tvType) {
            this.posterUrl = item.imgPath
        }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val key = url.substringAfterLast("/")
        Log.d(TAG, "load START key=$key url=$url")
        val (pub, priv) = getPConfig()

        // Detail
        val detailParams = mapOf(
            "ispath" to "false", "cinema" to "1", "device" to "1",
            "player" to "CkPlayer", "tech" to "HLS", "country" to "HU",
            "lang" to "cns", "v" to "1", "id" to key, "region" to "GL.",
        )
        val detailApiUrl = buildYfsUrl("$apiBase/v3/video/detail", detailParams, pub, priv)
        Log.d(TAG, "load detailApiUrl=$detailApiUrl")
        val detailResp = apiGet(detailApiUrl).parsed<YfspApiResponse<YfspDetail>>()
        Log.d(TAG, "load detailApiCode=${detailResp.data?.code} infoSize=${detailResp.data?.info?.size}")
        val detail = detailResp.data?.info?.firstOrNull()
            ?: throw ErrorLoadingException("No detail for key=$key").also {
                Log.e(TAG, "load FAIL: detail info is null for key=$key")
            }

        val title = detail.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("No title for key=$key").also {
                Log.e(TAG, "load FAIL: blank title for key=$key")
            }
        val posterUrl = detail.imgPath
        val plot      = detail.contxt?.trim()?.takeIf { it.isNotEmpty() }
        val actors    = detail.stars?.filter { it.isNotEmpty() }?.map { ActorData(Actor(it)) }
        val tags      = listOfNotNull(detail.language, detail.regional).filter { it.isNotEmpty() }
        val year      = detail.postYear

        Log.d(TAG, "load detail title='$title' id=${detail.id} isFilm=${detail.isFilm} isSerial=${detail.isSerial} serialCount=${detail.serialCount} year=$year actors=${actors?.size} tags=$tags")

        // Play endpoint → episode IDs
        val playParams = mapOf(
            "cinema" to "1", "id" to key, "lang" to "cns", "usersign" to "1",
            "region" to "GL.", "device" to "1", "a" to "1", "isMasterSupport" to "1",
        )
        val playApiUrl = buildYfsUrl("$apiBase/v3/video/play", playParams, pub, priv)
        Log.d(TAG, "load playApiUrl=$playApiUrl")
        val playResp = runCatching { apiGet(playApiUrl).parsed<YfspApiResponse<YfspPlayInfo>>() }
            .onFailure { Log.e(TAG, "load play request EXCEPTION ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        val playInfo = playResp?.data?.info?.firstOrNull()
        Log.d(TAG, "load playApiCode=${playResp?.data?.code} playInfoNull=${playInfo == null}")

        val ep1MediaKey = playInfo?.mediaKey ?: ""
        val ep1UniqueId = playInfo?.clarity?.firstOrNull { it.isVIP == false && it.isEnabled == true }?.uniqueID
            ?: playInfo?.clarity?.firstOrNull { it.isEnabled == true }?.uniqueID
        val ep1Title    = playInfo?.mediaTitle ?: "01"
        Log.d(TAG, "load ep1 mediaKey=$ep1MediaKey uniqueId=$ep1UniqueId title=$ep1Title clarityCount=${playInfo?.clarity?.size}")

        val isMovie = detail.isFilm == true || (detail.isSerial == false && (detail.serialCount ?: 1) <= 1)
        Log.d(TAG, "load isMovie=$isMovie (isFilm=${detail.isFilm} isSerial=${detail.isSerial} serialCount=${detail.serialCount})")

        if (isMovie) {
            Log.d(TAG, "load -> MovieLoadResponse title='$title' episodeData=${ep1UniqueId?.toString() ?: "(empty)"}")
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
            Log.d(TAG, "load adding ep1 uniqueId=$ep1UniqueId title=$ep1Title")
            episodes += newEpisode(ep1UniqueId.toString()) {
                name    = ep1Title
                episode = ep1Title.toIntOrNull() ?: 1
            }
        } else {
            Log.w(TAG, "load ep1UniqueId is null, ep1 will be missing from list")
        }

        var curKey = ep1MediaKey
        val maxEps = (detail.serialCount ?: 100).coerceAtMost(200)
        Log.d(TAG, "load chaining getnextvideo curKey=$curKey maxEps=$maxEps")

        while (curKey.isNotEmpty() && episodes.size < maxEps) {
            val nextParams = mapOf("cinema" to "1", "id" to curKey)
            val nextApiUrl = buildYfsUrl("$apiBase/v3/video/getnextvideo", nextParams, pub, priv)
            Log.d(TAG, "load getnextvideo[${episodes.size}] url=$nextApiUrl")
            val nextEp = runCatching {
                apiGet(nextApiUrl).parsed<YfspApiResponse<YfspNextEp>>().data?.info?.firstOrNull()
            }.onFailure {
                Log.e(TAG, "load getnextvideo EXCEPTION ${it.javaClass.simpleName}: ${it.message}, stopping chain")
            }.getOrNull() ?: break

            if (nextEp.id == null) {
                Log.w(TAG, "load getnextvideo returned null id, stopping chain")
                break
            }
            val epId = nextEp.id
            val epTitle = nextEp.title ?: "${episodes.size + 1}"
            Log.d(TAG, "load ep[${episodes.size + 1}] title=$epTitle id=$epId nextKey=${nextEp.key}")
            episodes += newEpisode(epId.toString()) {
                name    = epTitle
                episode = epTitle.toIntOrNull() ?: (episodes.size + 1)
            }
            curKey = nextEp.key ?: ""
        }

        Log.d(TAG, "load -> TvSeriesLoadResponse title='$title' episodes=${episodes.size} tvType=TvSeries")
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
        Log.d(TAG, "loadLinks START data='$data' isCasting=$isCasting")
        val episodeId = data.toLongOrNull() ?: run {
            Log.w(TAG, "loadLinks FAIL: data='$data' is not a numeric episode id")
            return false
        }

        // MasterPlayList returns a valid M3U8 master playlist — no signing required.
        val masterUrl = "$uploadBase/api/video/MasterPlayList?id=$episodeId"
        Log.d(TAG, "loadLinks masterUrl=$masterUrl")

        val m3u8Body = runCatching { apiGet(masterUrl).text }
            .onFailure { Log.e(TAG, "loadLinks MasterPlayList EXCEPTION ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull() ?: run {
                Log.w(TAG, "loadLinks FAIL: could not fetch MasterPlayList for episodeId=$episodeId")
                return false
            }
        Log.d(TAG, "loadLinks m3u8Body length=${m3u8Body.length} preview=${m3u8Body.take(60).replace("\n", "\\n")}")

        // Extract the first HLS chunklist URL from the M3U8
        val hlsUrl = m3u8Body.lines()
            .firstOrNull { it.startsWith("http") && (it.contains(".m3u8") || it.contains("chunklist")) }
            ?: run {
                Log.w(TAG, "loadLinks FAIL: no HLS URL found in MasterPlayList body (episodeId=$episodeId)")
                return false
            }

        Log.d(TAG, "loadLinks hlsUrl=${hlsUrl.take(80)}")
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
        Log.d(TAG, "loadLinks END callback invoked for episodeId=$episodeId")
        return true
    }
}
