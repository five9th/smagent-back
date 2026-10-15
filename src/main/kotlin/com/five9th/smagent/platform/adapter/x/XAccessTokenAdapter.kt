package com.five9th.smagent.platform.adapter.x

import com.five9th.smagent.config.AppProperties
import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.CredentialFieldKind
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.platform.CredentialParser
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.platform.PublishCommand
import com.five9th.smagent.platform.PublishResult
import com.five9th.smagent.platform.SocialPlatformAdapter
import com.five9th.smagent.platform.VerifyResult
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * X posting as the authenticated user (personal account) via
 * `POST /2/tweets` (needs the tweet.write + users.read scopes).
 *
 * Credentials: { "accessToken": "...", "refreshToken": "..."? } — an OAuth2
 * user token from the app's PKCE flow (`GET /api/oauth/x/authorize/{id}`, see
 * oauth/x). X access tokens expire after ~2 hours, so practical use requires
 * the refresh token that the same flow stores (scope `offline.access`); the
 * adapter refreshes automatically whenever the API answers 401. A manually
 * pasted token without a refresh token works until it expires.
 *
 * Refresh tokens are single-use: every refresh rotates the pair, and the new
 * values travel back on [VerifyResult]/[PublishResult] (and on
 * [PlatformException] when the call failed after a successful refresh) so the
 * caller persists them — see SocialPlatformAdapter's rotation contract.
 * Concurrency note: two simultaneous calls on one account race the refresh —
 * exactly one rotation wins, the loser fails with invalid_grant and the next
 * call succeeds with the persisted token; accepted for a single-owner app.
 */
@Component
class XAccessTokenAdapter(
    props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : SocialPlatformAdapter {

    override val platform = Platform.X
    override val authMethod = AuthMethod.ACCESS_TOKEN
    override val credentialFields = mapOf(
        "accessToken" to CredentialFieldKind.TOKEN,
        "refreshToken" to CredentialFieldKind.TOKEN,
    )
    override val credentialsMayBeBlank = setOf("refreshToken")
    override val canPublish = true

    private val x = props.x
    private val client = restClientBuilder.baseUrl(x.baseUrl).build()
    private val mapper = JsonMapper.builder().build()

    override fun verify(credentialsJson: String): VerifyResult = withRotation {
        val creds = CredentialParser.parse(credentialsJson, *credentialFields.keys.toTypedArray())
        val me = me(creds, this)
        VerifyResult(displayName = "${me.name} (@${me.username})", refreshedCredentials = values)
    }

    override fun publish(credentialsJson: String, command: PublishCommand): PublishResult = withRotation {
        val creds = CredentialParser.parse(credentialsJson, *credentialFields.keys.toTypedArray())
        val text = command.text +
            (command.link?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\n$it" } ?: "")

        // author_id expansion usually returns the handle in the same response;
        // without it one extra lookup resolves the post URL
        val body: JsonNode = callWithRetry(creds, this) { token ->
            mapper.readTree(
                client.post()
                    .uri("/2/tweets?expansions=author_id&user.fields=username")
                    .headers { it.setBearerAuth(token) }
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(mapOf("text" to text)))
                    .retrieve()
                    .body(String::class.java) ?: ""
            )
        }
        val id = body.path("data").path("id").asStringOrNull()
            ?: throw PlatformException("X publish response did not include a post id")
        val users = body.path("includes").path("users")
        val expandedUsername = if (users.isArray) {
            users.firstOrNull()?.path("username")?.asStringOrNull()
        } else {
            null
        }
        val username = expandedUsername ?: me(creds, this).username
        PublishResult(
            externalUrl = "https://x.com/$username/status/$id",
            refreshedCredentials = values,
        )
    }

    private data class Me(val name: String, val username: String)

    /** The token's user via GET /2/users/me. */
    private fun me(creds: Map<String, String>, rotation: Rotation): Me {
        val body: JsonNode = callWithRetry(creds, rotation) { token ->
            mapper.readTree(
                client.get()
                    .uri("/2/users/me")
                    .headers { it.setBearerAuth(token) }
                    .retrieve()
                    .body(String::class.java) ?: ""
            )
        }
        val data = body.path("data")
        val username = data.path("username").asStringOrNull()
            ?: throw PlatformException("X users/me response did not include a username")
        return Me(
            name = data.path("name").asStringOrNull() ?: username,
            username = username,
        )
    }

    /**
     * Runs [block] with the current access token; on a 401 refreshes once and
     * retries. Any other failure surfaces as an [PlatformException].
     */
    private fun <T> callWithRetry(
        creds: Map<String, String>,
        rotation: Rotation,
        block: (String) -> T,
    ): T = try {
        block(rotation.token(creds))
    } catch (e: RestClientResponseException) {
        if (e.statusCode.value() != 401) throw apiError(e)
        val token = refresh(creds, rotation)
        try {
            block(token)
        } catch (e: RestClientResponseException) {
            throw apiError(e)
        }
    }

    /**
     * Refresh grant against the token endpoint (confidential client: app
     * credentials as HTTP Basic auth, not form fields). X rotates the refresh
     * token on use — the new pair is recorded for the caller to persist.
     */
    private fun refresh(creds: Map<String, String>, rotation: Rotation): String {
        val current = rotation.values ?: creds
        val refreshToken = current.getValue("refreshToken").trim()
        if (refreshToken.isEmpty()) {
            throw PlatformException(
                "X access token rejected (401) and no refresh token is stored — re-run the OAuth flow"
            )
        }

        val form = LinkedMultiValueMap<String, String>()
        form.add("grant_type", "refresh_token")
        form.add("refresh_token", refreshToken)

        val body: JsonNode = try {
            mapper.readTree(
                client.post()
                    .uri("/2/oauth2/token")
                    .headers { it.setBasicAuth(x.clientId, x.clientSecret) }
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String::class.java) ?: ""
            )
        } catch (e: RestClientResponseException) {
            throw PlatformException(
                "X token refresh failed (${e.statusCode.value()}): ${extractErrorMessage(e)} — " +
                    "re-run the OAuth flow to reconnect the account",
                e,
            )
        } catch (e: Exception) {
            throw PlatformException("X token endpoint call failed: ${e.message}", e)
        }

        val accessToken = body.path("access_token").asStringOrNull()
            ?: throw PlatformException("X refresh response did not include access_token")
        val refreshed = mutableMapOf("accessToken" to accessToken)
        // rotation: the old refresh token is dead; keep it only if X didn't issue a new one
        body.path("refresh_token").asStringOrNull()?.let { refreshed["refreshToken"] = it }
        rotation.record(refreshed)
        return accessToken
    }

    /**
     * The rotation accumulator for one verify/publish run plus the boundary
     * that attaches it to whatever escapes (a failure after a successful
     * refresh must still persist the rotated pair).
     */
    private class Rotation {
        var values: Map<String, String>? = null
            private set

        fun token(creds: Map<String, String>): String =
            values?.get("accessToken") ?: creds.getValue("accessToken")

        fun record(refreshed: Map<String, String>) {
            values = (values ?: emptyMap()) + refreshed
        }
    }

    private inline fun <T> withRotation(block: Rotation.() -> T): T {
        val rotation = Rotation()
        return try {
            rotation.block()
        } catch (e: PlatformException) {
            // already carrying rotation (refresh failure) or nothing rotated: as-is
            if (rotation.values == null || e.refreshedCredentials != null) throw e
            throw PlatformException(e.message ?: "", e.cause, rotation.values)
        } catch (e: Exception) {
            throw PlatformException("X API call failed: ${e.message}", e)
        }
    }

    private fun apiError(e: RestClientResponseException): PlatformException {
        val hint = if (e.statusCode.value() == 401) {
            " (access token rejected even after a refresh — re-run the OAuth flow)"
        } else {
            ""
        }
        return PlatformException("X API error (${e.statusCode.value()}): ${extractErrorMessage(e)}$hint", e)
    }

    /** X errors are {"title":"...","detail":"..."}; token endpoint stays OAuth-style. */
    private fun extractErrorMessage(e: RestClientResponseException): String = try {
        val root = mapper.readTree(e.responseBodyAsString)
        root.path("detail").asStringOrNull()
            ?: root.path("error_description").asStringOrNull()
            ?: root.path("title").asStringOrNull()
            ?: e.statusText
    } catch (_: Exception) {
        e.statusText
    }

    private fun JsonNode.asStringOrNull(): String? =
        if (isNull || isMissingNode) null else asString()
}
