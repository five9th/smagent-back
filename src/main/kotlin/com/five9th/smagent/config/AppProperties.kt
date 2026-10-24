package com.five9th.smagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "smagent")
class AppProperties(
    var baseUrl: String = "",
    var cors: Cors = Cors(),
    var facebook: Facebook = Facebook(),
    var reddit: Reddit = Reddit(),
    var linkedin: LinkedIn = LinkedIn(),
    var x: X = X(),
) {
    /** SPA origins allowed to call the API (session cookie needs credentials). */
    class Cors(var allowedOrigins: List<String> = listOf("http://localhost:5173"))

    /**
     * OAuth note: the Login dialog URL is derived from [graphApiVersion]
     * (`https://www.facebook.com/{v}/dialog/oauth`); the flow yields a user
     * token, which the callback extends (fb_exchange_token) and trades for a
     * non-expiring Page token via /me/accounts (see oauth/facebook).
     */
    class Facebook(
        var baseUrl: String = "https://graph.facebook.com",
        var graphApiVersion: String = "v26.0",
        var callbackEndpoint: String = "/api/oauth/facebook/callback",
        var scope: String = "pages_show_list,pages_manage_posts,pages_read_engagement",

        var clientId: String = "",
        var clientSecret: String = "",
    )

    class Reddit(
        var baseUrl: String = "https://oauth.reddit.com",
        var tokenUrl: String = "https://www.reddit.com/api/v1/access_token",
    )

    /** apiVersion is the LinkedIn-Version header (YYYYMM); each lives ~12 months. */
    class LinkedIn(
        var baseUrl: String = "https://api.linkedin.com",
        var apiVersion: String = "202609",
        var callbackEndpoint: String = "/",
        var scopePersonal: String = "",
        var scopeCompany: String = "",

        var clientId: String = "",
        var clientSecret: String = "",
    )

    /**
     * X OAuth2 is PKCE (S256) with a confidential client: the token endpoint
     * wants the app credentials as HTTP Basic auth, not form fields. Access
     * tokens last ~2h and refresh tokens rotate on every use — the adapter
     * persists the rotated pair after each refresh (see XAccessTokenAdapter).
     */
    class X(
        var baseUrl: String = "https://api.x.com",
        var authorizeUrl: String = "https://x.com/i/oauth2/authorize",
        var callbackEndpoint: String = "/api/oauth/x/callback",
        var scope: String = "tweet.read tweet.write users.read offline.access",

        var clientId: String = "",
        var clientSecret: String = "",
    )
}
