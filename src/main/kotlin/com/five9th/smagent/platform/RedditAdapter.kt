package com.five9th.smagent.platform

import com.five9th.smagent.config.AppProperties
import com.five9th.smagent.domain.Platform
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Reddit posting as a "script" app using the OAuth2 password grant
 * (works for the developer's own account; tokens last ~1h and are cached).
 *
 * Credentials: { "clientId", "clientSecret", "username", "password", "subreddit" }
 */
@Component
class RedditAdapter(
    props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : SocialPlatformAdapter {

    override val platform = Platform.REDDIT
    override val requiredCredentialFields =
        setOf("clientId", "clientSecret", "username", "password", "subreddit")

    private val apiClient = restClientBuilder.baseUrl(props.reddit.baseUrl).build()
    private val tokenClient = restClientBuilder.baseUrl(props.reddit.tokenUrl).build()
    private val mapper = JsonMapper.builder().build()

    private data class CachedToken(val token: String, val expiresAt: Instant)

    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    override fun verify(credentialsJson: String): VerifyResult {
        val creds = CredentialParser.parse(credentialsJson, *requiredCredentialFields.toTypedArray())
        val token = accessToken(creds)
        val ua = userAgent(creds.getValue("username"))
        val body = parse {
            apiClient.get()
                .uri("/api/v1/me")
                .headers { h ->
                    h.setBearerAuth(token)
                    h.set("User-Agent", ua)
                }
                .retrieve()
                .body(String::class.java) ?: ""
        }
        val name = body.path("name").asStringOrNull()
            ?: throw PlatformException("Reddit verify response did not include a user name")
        return VerifyResult(displayName = "u/$name -> r/${normalizeSubreddit(creds.getValue("subreddit"))}")
    }

    override fun publish(credentialsJson: String, command: PublishCommand): PublishResult {
        val creds = CredentialParser.parse(credentialsJson, *requiredCredentialFields.toTypedArray())
        val title = command.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw PlatformException("Reddit posts require a non-empty title")

        val form = LinkedMultiValueMap<String, String>()
        form.add("sr", normalizeSubreddit(creds.getValue("subreddit")))
        form.add("kind", "self")
        form.add("title", title)
        form.add("text", command.text)
        form.add("api_type", "json")
        form.add("resubmit", "true")

        val token = accessToken(creds)
        val ua = userAgent(creds.getValue("username"))
        val body = parse {
            apiClient.post()
                .uri("/api/submit")
                .headers { h ->
                    h.setBearerAuth(token)
                    h.set("User-Agent", ua)
                }
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String::class.java) ?: ""
        }

        // Reddit answers 200 with an errors array inside json
        val errors = body.path("json").path("errors")
        if (errors.isArray && errors.size() > 0) {
            val first = errors.get(0)
            val message = if (first.isArray && first.size() >= 2) first.get(1).asString() else first.asString()
            throw PlatformException("Reddit rejected the post: $message")
        }
        val permalink = body.path("json").path("data").path("permalink").asStringOrNull()
            ?: throw PlatformException("Reddit publish response did not include a permalink")
        return PublishResult(externalUrl = "https://www.reddit.com$permalink")
    }

    /** Password grant; cached until shortly before expiry (~1h tokens). */
    private fun accessToken(creds: Map<String, String>): String {
        val cacheKey = "${creds.getValue("clientId")}:${creds.getValue("username")}"
        tokenCache[cacheKey]?.let { cached ->
            if (cached.expiresAt.isAfter(Instant.now().plusSeconds(MARGIN_SECONDS))) return cached.token
        }

        val username = creds.getValue("username")
        val form = LinkedMultiValueMap<String, String>()
        form.add("grant_type", "password")
        form.add("username", username)
        form.add("password", creds.getValue("password"))

        val body: JsonNode = try {
            mapper.readTree(
                tokenClient.post()
                    .headers { h ->
                        h.setBasicAuth(creds.getValue("clientId"), creds.getValue("clientSecret"))
                        h.set("User-Agent", userAgent(username))
                    }
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String::class.java) ?: ""
            )
        } catch (e: RestClientResponseException) {
            val hint = if (e.statusCode.value() == 401) {
                "invalid clientId/clientSecret, or the account uses 2FA/app-only auth"
            } else {
                e.statusText
            }
            throw PlatformException("Reddit authentication failed ($hint)", e)
        } catch (e: Exception) {
            throw PlatformException("Reddit token request failed: ${e.message}", e)
        }

        val token = body.path("access_token").asStringOrNull()
            ?: throw PlatformException("Reddit token response did not include access_token")
        val expiresIn = body.path("expires_in").asLongOrNull() ?: 3600L
        tokenCache[cacheKey] = CachedToken(token, Instant.now().plusSeconds(expiresIn))
        return token
    }

    private fun parse(block: () -> String): JsonNode = try {
        mapper.readTree(block())
    } catch (e: PlatformException) {
        throw e
    } catch (e: RestClientResponseException) {
        throw PlatformException("Reddit API error (${e.statusCode.value()}): ${e.statusText}", e)
    } catch (e: Exception) {
        throw PlatformException("Reddit API call failed: ${e.message}", e)
    }

    private fun userAgent(username: String) = "linux:smagent:v$APP_VERSION (by /u/$username)"

    private fun normalizeSubreddit(name: String): String = name.trim().removePrefix("r/").removePrefix("/r/")

    private fun JsonNode.asStringOrNull(): String? =
        if (isNull || isMissingNode) null else asString()

    private fun JsonNode.asLongOrNull(): Long? =
        if (isNull || isMissingNode) null else asLong()

    companion object {
        private const val MARGIN_SECONDS = 60L
        private const val APP_VERSION = "0.0.1"
    }
}
