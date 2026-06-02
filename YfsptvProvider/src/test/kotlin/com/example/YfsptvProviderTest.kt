package com.example

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Tests for YfsptvProvider.
 *
 * Uses the production API with the hardcoded fallback keys (fetched on demand in the
 * live provider).  All live tests are tagged with "live" in their names.
 *
 * Signing algorithm (reversed from main.js):
 *   qs      = params.joinToString("") { "key=value&" }   // trailing '&'
 *   signing = PUB_KEY + "&" + qs.lowercase() + PRIV_KEY
 *   vv      = MD5(signing)
 */
class YfsptvProviderTest {

    private val pub  = YfsptvProvider.FALLBACK_PUB_KEY
    private val priv = YfsptvProvider.FALLBACK_PRIV_KEY

    private fun signedUrl(base: String, params: Map<String, String>, urlParams: Map<String, String> = params): String =
        buildYfsUrl(base, params, pub, priv, urlParams)

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            connectTimeout = 15_000
            readTimeout    = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer",    "https://www.yfsp.tv/")
        }
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }

    // ── Unit tests: signing ──────────────────────────────────────────────────

    @Test fun `md5Hex empty string`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
    }

    @Test fun `md5Hex known value`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5Hex("hello"))
    }

    @Test fun `yfsSign output is 32-char lowercase hex`() {
        val vv = yfsSign(mapOf("page" to "1", "cinema" to "1"), pub, priv)
        assertTrue("vv must be 32-char lowercase hex", vv.matches(Regex("[0-9a-f]{32}")))
    }

    @Test fun `yfsSign includes trailing ampersand per param`() {
        // Manually reproduce: "page=1&" + "cinema=1&" → total queryStr has trailing '&'
        val params   = mapOf("page" to "1", "cinema" to "1")
        val qs       = params.entries.joinToString("") { "${it.key}=${it.value}&" }
        val signing  = "$pub&${qs.lowercase()}$priv"
        val expected = md5Hex(signing)
        assertEquals(expected, yfsSign(params, pub, priv))
    }

    @Test fun `yfsSign is case-insensitive on param values`() {
        // lowercase is applied to the whole qs segment
        val vv1 = yfsSign(mapOf("key" to "ABC"), pub, priv)
        val vv2 = yfsSign(mapOf("key" to "abc"), pub, priv)
        assertEquals(vv1, vv2)
    }

    @Test fun `buildYfsUrl appends vv and pub`() {
        val url = signedUrl("https://m10.yfsp.tv/api/list/index", mapOf("page" to "1", "cinema" to "1"))
        assertTrue(url.contains("vv="))
        assertTrue(url.contains("pub="))
    }

    // ── Integration: movie list ──────────────────────────────────────────────

    @Test fun `live - movie list returns items`() {
        val params = mapOf("page" to "1", "cid" to "0,1,3", "size" to "20", "isn" to "0", "isfree" to "-1", "cinema" to "1")
        val url    = signedUrl("https://m10.yfsp.tv/api/list/index", params)
        val body   = get(url)
        println("[live] movie list: ${body.take(200)}")
        val data   = JSONObject(body).getJSONObject("data")
        assertEquals("API code must be 0 (success)", 0, data.getInt("code"))
        val info   = data.getJSONArray("info")
        assertTrue("Movie list must have items", info.length() > 0)
        val first  = info.getJSONObject(0)
        assertTrue("Item must have 'key'",   first.has("key"))
        assertTrue("Item must have 'title'", first.has("title"))
        println("[live] first movie: title=${first.optString("title")} key=${first.optString("key")}")
    }

    // ── Integration: TV series list ──────────────────────────────────────────

    @Test fun `live - TV series list returns items`() {
        val params = mapOf("page" to "1", "cid" to "0,1,4", "size" to "20", "isn" to "0", "isfree" to "-1", "cinema" to "1")
        val url    = signedUrl("https://m10.yfsp.tv/api/list/index", params)
        val body   = get(url)
        val data   = JSONObject(body).getJSONObject("data")
        assertEquals(0, data.getInt("code"))
        val info   = data.getJSONArray("info")
        assertTrue("TV list must have items", info.length() > 0)
        println("[live] first TV series: title=${info.getJSONObject(0).optString("title")}")
    }

    // ── Integration: search ──────────────────────────────────────────────────

    @Test fun `live - search returns results for Chinese query`() {
        val query    = "甄嬛"
        val rawParams = mapOf("cinema" to "1", "tags" to query, "size" to "20", "page" to "1", "orderby" to "4")
        val urlParams = rawParams + mapOf("tags" to URLEncoder.encode(query, "UTF-8"))
        val url      = signedUrl("https://rankv21.yfsp.tv/v3/list/briefsearch", rawParams, urlParams)
        val body     = get(url)
        println("[live] search: ${body.take(300)}")
        val data     = JSONObject(body).getJSONObject("data")
        assertEquals(0, data.getInt("code"))
        val info     = data.getJSONArray("info")
        assertTrue("Search info must have an element", info.length() > 0)
        val result   = info.getJSONObject(0).getJSONArray("result")
        assertTrue("Search must return at least one result", result.length() > 0)
        val first    = result.getJSONObject(0)
        println("[live] first result: title=${first.optString("title")} contxt=${first.optString("contxt").take(20)}")
    }

    // ── Integration: detail ──────────────────────────────────────────────────

    @Test fun `live - detail returns title and metadata for movie`() {
        val videoKey = "s7f2geyU7RM"  // 致命之旅 (movie)
        val params   = mapOf(
            "ispath" to "false", "cinema" to "1", "device" to "1",
            "player" to "CkPlayer", "tech" to "HLS", "country" to "HU",
            "lang" to "cns", "v" to "1", "id" to videoKey, "region" to "GL.",
        )
        val url  = signedUrl("https://m10.yfsp.tv/v3/video/detail", params)
        val body = get(url)
        val data = JSONObject(body).getJSONObject("data")
        assertEquals(0, data.getInt("code"))
        val info  = data.getJSONArray("info")
        assertTrue("Detail must have info", info.length() > 0)
        val det   = info.getJSONObject(0)
        println("[live] detail title=${det.optString("title")} id=${det.optInt("id")}")
        assertEquals("致命之旅", det.optString("title"))
        assertTrue("Detail must have numeric id > 0", det.optInt("id") > 0)
    }

    // ── Integration: play + episode list ────────────────────────────────────

    @Test fun `live - play for movie returns free HLS clarity item`() {
        val videoKey = "s7f2geyU7RM"  // 致命之旅 (movie)
        val params   = mapOf(
            "cinema" to "1", "id" to videoKey, "lang" to "cns", "usersign" to "1",
            "region" to "GL.", "device" to "1", "a" to "1", "isMasterSupport" to "1",
        )
        val url  = signedUrl("https://m10.yfsp.tv/v3/video/play", params)
        val body = get(url)
        val data = JSONObject(body).getJSONObject("data")
        assertEquals(0, data.getInt("code"))
        val info     = data.getJSONArray("info")
        assertTrue(info.length() > 0)
        val playInfo = info.getJSONObject(0)
        val mediaKey = playInfo.optString("mediaKey")
        assertFalse("mediaKey must not be empty", mediaKey.isNullOrEmpty())
        println("[live] movie play mediaKey=$mediaKey mediaTitle=${playInfo.optString("mediaTitle")}")

        val clarity  = playInfo.getJSONArray("clarity")
        val freeItem = (0 until clarity.length())
            .map { clarity.getJSONObject(it) }
            .firstOrNull { !it.optBoolean("isVIP") && it.optBoolean("isEnabled") }
        assertNotNull("Must have a free (non-VIP) clarity item", freeItem)
        println("[live] free clarity uniqueID=${freeItem!!.optLong("uniqueID")}")
    }

    @Test fun `live - getnextvideo chains at least 3 episodes for a series`() {
        // 盛唐奇案 (serialCount=26)
        val seriesKey = "BIXnUnrZ54E"
        // Get ep1 mediaKey from play
        val playParams = mapOf(
            "cinema" to "1", "id" to seriesKey, "lang" to "cns", "usersign" to "1",
            "region" to "GL.", "device" to "1", "a" to "1", "isMasterSupport" to "1",
        )
        val playBody  = get(signedUrl("https://m10.yfsp.tv/v3/video/play", playParams))
        val playData  = JSONObject(playBody).getJSONObject("data")
        assertEquals(0, playData.getInt("code"))
        var curKey = playData.getJSONArray("info").getJSONObject(0).optString("mediaKey")
        assertFalse("ep1 mediaKey must not be empty", curKey.isNullOrEmpty())
        println("[live] series ep1 mediaKey=$curKey")

        // Chain through 3 episodes
        repeat(3) { idx ->
            val nextParams = mapOf("cinema" to "1", "id" to curKey)
            val nextBody   = get(signedUrl("https://m10.yfsp.tv/v3/video/getnextvideo", nextParams))
            val nextData   = JSONObject(nextBody).getJSONObject("data")
            assertEquals(0, nextData.getInt("code"))
            val ep = nextData.getJSONArray("info").getJSONObject(0)
            println("[live] ep${idx + 2}: title=${ep.optString("title")} id=${ep.optLong("id")} key=${ep.optString("key")}")
            assertTrue("ep id must be > 0", ep.optLong("id") > 0)
            curKey = ep.optString("key")
            assertFalse("next ep key must not be empty", curKey.isNullOrEmpty())
        }
    }

    // ── Integration: MasterPlayList (loadLinks) ──────────────────────────────

    @Test fun `live - MasterPlayList returns valid M3U8 for movie episode`() {
        // 致命之旅 movie: uniqueID fetched from the play test above (45087 area)
        // Use the numeric uniqueID from play clarity
        val videoKey = "s7f2geyU7RM"
        val playParams = mapOf(
            "cinema" to "1", "id" to videoKey, "lang" to "cns", "usersign" to "1",
            "region" to "GL.", "device" to "1", "a" to "1", "isMasterSupport" to "1",
        )
        val playBody  = get(signedUrl("https://m10.yfsp.tv/v3/video/play", playParams))
        val playInfo  = JSONObject(playBody).getJSONObject("data").getJSONArray("info").getJSONObject(0)
        val clarity   = playInfo.getJSONArray("clarity")
        val freeItem  = (0 until clarity.length())
            .map { clarity.getJSONObject(it) }
            .first { !it.optBoolean("isVIP") && it.optBoolean("isEnabled") }
        val uniqueId  = freeItem.optLong("uniqueID")
        assertTrue("uniqueID must be > 0", uniqueId > 0)

        val masterUrl = "https://upload.yfsp.tv/api/video/MasterPlayList?id=$uniqueId"
        val m3u8Body  = get(masterUrl)
        println("[live] MasterPlayList id=$uniqueId: ${m3u8Body.take(200)}")
        assertTrue("Response must start with #EXTM3U", m3u8Body.trimStart().startsWith("#EXTM3U"))
        val hlsLine = m3u8Body.lines().firstOrNull { it.startsWith("http") }
        assertNotNull("M3U8 must contain an HLS URL", hlsLine)
        println("[live] HLS URL: ${hlsLine?.take(80)}")
    }
}
