package com.five9th.smagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "smagent")
class AppProperties(
    var cors: Cors = Cors(),
    var facebook: Facebook = Facebook(),
    var reddit: Reddit = Reddit(),
) {
    /** SPA origins allowed to call the API (session cookie needs credentials). */
    class Cors(var allowedOrigins: List<String> = listOf("http://localhost:5173"))

    class Facebook(var graphApiVersion: String = "v23.0")

    class Reddit(
        var baseUrl: String = "https://oauth.reddit.com",
        var tokenUrl: String = "https://www.reddit.com/api/v1/access_token",
    )
}
