package com.five9th.smagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "smagent")
class AppProperties(
    var baseUrl: String = "",
    var cors: Cors = Cors(),
    var facebook: Facebook = Facebook(),
    var reddit: Reddit = Reddit(),
    var linkedin: LinkedIn = LinkedIn(),
) {
    /** SPA origins allowed to call the API (session cookie needs credentials). */
    class Cors(var allowedOrigins: List<String> = listOf("http://localhost:5173"))

    class Facebook(var graphApiVersion: String = "v26.0")

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
}
