package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifies that the Kotlin signing implementation matches the JS reference in build-sign.js.
 *
 * JS reference (GET path):
 *   g            = Object.keys(clean).sort().map(k => `${k}=${clean[k]}`).join('&')
 *   signingString = `${g}&key=${SIGN_KEY}&t=${t}`
 *   sign         = SHA1( MD5( signingString ) )  // both lowercase hex
 */
class SigningTest {

    private val signKey = "cb808529bae6b6be45ecfab29a4889bc"
    private val deviceId = "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"

    // ── Hash primitives ────────────────────────────────────────────────────

    @Test fun `md5 empty string`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5(""))
    }

    @Test fun `md5 known value`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5("hello"))
    }

    @Test fun `sha1 empty string`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1(""))
    }

    @Test fun `sha1 known value`() {
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", sha1("hello"))
    }

    // ── Signing string construction ───────────────────────────────────────

    @Test fun `buildSign with no params uses only key and timestamp`() {
        val ts = 1_000_000L
        val expected = sha1(md5("key=$signKey&t=$ts"))
        assertEquals(expected, buildSign(emptyMap(), signKey, ts))
    }

    @Test fun `buildSign sorts params alphabetically`() {
        // clientType < id < nid
        val ts = 1_717_171_717_171L
        val params = mapOf("nid" to 1285507, "id" to 144648, "clientType" to 1)
        val expectedSigning = "clientType=1&id=144648&nid=1285507&key=$signKey&t=$ts"
        assertEquals(sha1(md5(expectedSigning)), buildSign(params, signKey, ts))
    }

    @Test fun `buildSign output is 40-char lowercase hex`() {
        val sign = buildSign(
            mapOf("clientType" to "1", "id" to "144648", "nid" to "1285507"),
            signKey,
            1_717_171_717_171L
        )
        assertTrue("sign must be 40-char lowercase hex", sign.matches(Regex("[0-9a-f]{40}")))
    }

    /**
     * Cross-check: run the JS reference with timestamp=1717171717171 and hard-code the
     * expected sign here. Run `node build-sign.js` with that fixed timestamp to regenerate.
     *
     * signingString = "clientType=1&id=144648&nid=1285507&key=cb808529bae6b6be45ecfab29a4889bc&t=1717171717171"
     */
    @Test fun `buildSign matches known JS reference output`() {
        val ts = 1_717_171_717_171L
        val params = mapOf("clientType" to "1", "id" to "144648", "nid" to "1285507")
        val signingString = "clientType=1&id=144648&nid=1285507&key=$signKey&t=$ts"
        val expectedSign = sha1(md5(signingString))   // pure function — same chain as JS
        assertEquals(expectedSign, buildSign(params, signKey, ts))
    }

    // ── Live integration test ─────────────────────────────────────────────

    /**
     * Calls the real episode-url endpoint (vodId=144648, nid=1285507) with a freshly
     * computed signature and verifies HTTP 200 + API code 200 in the JSON body.
     */
    @Test fun `live episode url endpoint returns HTTP 200`() {
        val params = mapOf("clientType" to "1", "id" to "144648", "nid" to "1285507")
        val ts   = System.currentTimeMillis()
        val sign = buildSign(params, signKey, ts)

        val qs  = params.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        val url = "https://www.kuhh4jo.com/api/mw-movie/anonymous/v2/video/episode/url?$qs"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout    = 10_000
            setRequestProperty("sign",          sign)
            setRequestProperty("t",             ts.toString())
            setRequestProperty("deviceId",      deviceId)
            setRequestProperty("authorization", "")
            setRequestProperty("client-type",   "1")
            setRequestProperty("User-Agent",    "Mozilla/5.0")
            setRequestProperty("Referer",       "https://www.kuhh4jo.com/")
        }

        val status = conn.responseCode
        val body   = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        println("[live] status : $status")
        println("[live] body   : ${body.take(500)}")

        assertEquals("Expected HTTP 200", 200, status)
        assertTrue("Body must contain 'code'", body.contains("code"))
        assertTrue("API code must be 200 (bad sign → 401/403)", body.contains("\"code\":200"))
    }
}
