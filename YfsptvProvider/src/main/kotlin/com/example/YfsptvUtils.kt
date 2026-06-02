package com.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.security.MessageDigest

// ── Signing ─────────────────────────────────────────────────────────────────

internal fun md5Hex(input: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Computes the `vv` signature parameter for yfsp.tv API requests.
 *
 * Algorithm (reversed from main.js uriSignature + get_query):
 *   queryStr  = params.joinToString("") { "key=value&" }   // trailing '&' after each pair
 *   signing   = pubKey + "&" + queryStr.lowercase() + privKey
 *   vv        = MD5(signing)
 *
 * The final URL appends `&vv={vv}&pub={pubKey}`.
 */
internal fun yfsSign(params: Map<String, String>, pubKey: String, privKey: String): String {
    val qs = params.entries.joinToString("") { "${it.key}=${it.value}&" }
    val signing = pubKey + "&" + qs.lowercase() + privKey
    return md5Hex(signing)
}

/**
 * Builds a complete signed URL: base?params&vv={vv}&pub={pubKey}
 *
 * [rawParams] are used verbatim in both the signing string and the URL query.
 * Non-ASCII values (e.g. Chinese search terms) must be URL-encoded by the caller before
 * passing as [urlParams]; if [urlParams] is omitted, [rawParams] are used as-is in the URL.
 */
internal fun buildYfsUrl(
    base: String,
    rawParams: Map<String, String>,
    pubKey: String,
    privKey: String,
    urlParams: Map<String, String> = rawParams,
): String {
    val vv = yfsSign(rawParams, pubKey, privKey)
    val qs = urlParams.entries.joinToString("&") { "${it.key}=${it.value}" }
    return "$base?$qs&vv=$vv&pub=$pubKey"
}

// ── Data classes ─────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspApiData<T>(
    @JsonProperty("code") val code: Int?,
    @JsonProperty("info") val info: List<T>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspApiResponse<T>(
    @JsonProperty("ret")  val ret: Int?,
    @JsonProperty("data") val data: YfspApiData<T>?,
)

// list/index
@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspListItem(
    @JsonProperty("key")          val key: String?,
    @JsonProperty("title")        val title: String?,
    @JsonProperty("image")        val image: String?,    // poster URL in list
    @JsonProperty("atypeName")    val atypeName: String?,
    @JsonProperty("videoClassID") val videoClassID: String?,
    @JsonProperty("isSerial")     val isSerial: Boolean?,
    @JsonProperty("isFilm")       val isFilm: Boolean?,
)

// v3/video/detail  (info[0])
@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspDetail(
    @JsonProperty("id")          val id: Int?,
    @JsonProperty("key")         val key: String?,
    @JsonProperty("title")       val title: String?,
    @JsonProperty("imgPath")     val imgPath: String?,
    @JsonProperty("contxt")      val contxt: String?,   // plot/description
    @JsonProperty("post_Year")   val postYear: Int?,
    @JsonProperty("stars")       val stars: List<String>?,
    @JsonProperty("directors")   val directors: List<String>?,
    @JsonProperty("language")    val language: String?,
    @JsonProperty("regional")    val regional: String?,
    @JsonProperty("isSerial")    val isSerial: Boolean?,
    @JsonProperty("isFilm")      val isFilm: Boolean?,
    @JsonProperty("serialCount") val serialCount: Int?,
)

// v3/video/play  clarity items
@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspClarity(
    @JsonProperty("id")          val id: Long?,
    @JsonProperty("uniqueID")    val uniqueID: Long?,
    @JsonProperty("title")       val title: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("isVIP")       val isVIP: Boolean?,
    @JsonProperty("isEnabled")   val isEnabled: Boolean?,
    @JsonProperty("key")         val key: String?,
    @JsonProperty("memo")        val memo: String?,   // episode number string
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspFlvItem(
    @JsonProperty("isHls")   val isHls: Boolean?,
    @JsonProperty("result")  val result: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspPlayInfo(
    @JsonProperty("key")          val key: String?,
    @JsonProperty("mediaKey")     val mediaKey: String?,
    @JsonProperty("mediaTitle")   val mediaTitle: String?,
    @JsonProperty("clarity")      val clarity: List<YfspClarity>?,
    @JsonProperty("flvPathList")  val flvPathList: List<YfspFlvItem>?,
)

// v3/video/getnextvideo  (info[0])
@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspNextEp(
    @JsonProperty("title") val title: String?,
    @JsonProperty("id")    val id: Long?,
    @JsonProperty("key")   val key: String?,
)

// rankv21 briefsearch  (info[0].result[*])
@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspSearchResult(
    @JsonProperty("contxt")    val contxt: String?,  // video key in search context
    @JsonProperty("title")     val title: String?,
    @JsonProperty("imgPath")   val imgPath: String?,
    @JsonProperty("atypeName") val atypeName: String?,
    @JsonProperty("regional")  val regional: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YfspBriefSearchInfo(
    @JsonProperty("recordcount") val recordcount: Int?,
    @JsonProperty("result")      val result: List<YfspSearchResult>?,
)
