package com.five9th.smagent.common

import java.net.URI

/** Shared http(s) validation for client-supplied URLs (IllegalArgumentException -> 400). */
object Urls {

    private val HTTP_SCHEMES = setOf("http", "https")

    fun requireHttpUrl(url: String, field: String): String {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("$field is not a valid URL: '$url'")
        }
        if (uri.scheme !in HTTP_SCHEMES || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("$field must be an http(s) URL: '$url'")
        }
        return url
    }
}
