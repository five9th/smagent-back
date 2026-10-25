package com.five9th.smagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "smagent")
class AppProperties(
    val baseUrl: String = "",
    val cors: Cors = Cors(),
    val facebook: Facebook = Facebook(),
    val reddit: Reddit = Reddit(),
    val linkedin: LinkedIn = LinkedIn(),
    val x: X = X(),
) {
    /** SPA origins allowed to call the API (session cookie needs credentials). */
    class Cors(val allowedOrigins: List<String> = listOf("http://localhost:5173"))

    /**
     * OAuth note: the Login dialog URL is derived from [graphApiVersion]
     * (`https://www.facebook.com/{v}/dialog/oauth`); the flow yields a user
     * token, which the callback extends (fb_exchange_token) and trades for a
     * non-expiring Page token via /me/accounts (see oauth/facebook).
     */
    class Facebook(
        val baseUrl: String = "https://graph.facebook.com",
        val graphApiVersion: String = "v26.0",
        val callbackEndpoint: String = "/api/oauth/facebook/callback",
        val scope: String = "pages_show_list,pages_manage_posts,pages_read_engagement",

        val clientId: String = "",
        val clientSecret: String = "",
    )

    class Reddit(
        val baseUrl: String = "https://oauth.reddit.com",
        val tokenUrl: String = "https://www.reddit.com/api/v1/access_token",
    )

    /** apiVersion is the LinkedIn-Version header (YYYYMM); each lives ~12 months. */
    class LinkedIn(
        val baseUrl: String = "https://api.linkedin.com",
        val apiVersion: String = "202609",
        val callbackEndpoint: String = "/",
        val scopePersonal: String = "",
        val scopeCompany: String = "",

        val clientId: String = "",
        val clientSecret: String = "",
    )

    /**
     * X OAuth2 is PKCE (S256) with a confidential client: the token endpoint
     * wants the app credentials as HTTP Basic auth, not form fields. Access
     * tokens last ~2h and refresh tokens rotate on every use — the adapter
     * persists the rotated pair after each refresh (see XAccessTokenAdapter).
     */
    class X(
        val baseUrl: String = "https://api.x.com",
        val authorizeUrl: String = "https://x.com/i/oauth2/authorize",
        val callbackEndpoint: String = "/api/oauth/x/callback",
        val scope: String = "tweet.read tweet.write users.read offline.access",

        val clientId: String = "",
        val clientSecret: String = "",
    )
}
