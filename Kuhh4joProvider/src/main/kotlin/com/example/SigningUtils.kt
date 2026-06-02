package com.example

import java.security.MessageDigest

internal fun md5(input: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

internal fun sha1(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Builds the request signature used by kuhh4jo.com (GET requests).
 *
 * Algorithm (reverse-engineered from JS chunk 2844, module 49858):
 *   signingString = sortedParams.map("k=v").join("&") + "&key=<KEY>&t=<ts>"
 *   sign          = SHA1( MD5( signingString ) )     // both as lowercase hex
 */
internal fun buildSign(params: Map<String, Any>, signKey: String, timestamp: Long): String {
    val dataStr = params.entries
        .filter { it.value.toString().isNotEmpty() }
        .sortedBy { it.key }
        .joinToString("&") { "${it.key}=${it.value}" }
    val signingString = if (dataStr.isNotEmpty()) "$dataStr&key=$signKey&t=$timestamp"
                        else "key=$signKey&t=$timestamp"
    return sha1(md5(signingString))
}
