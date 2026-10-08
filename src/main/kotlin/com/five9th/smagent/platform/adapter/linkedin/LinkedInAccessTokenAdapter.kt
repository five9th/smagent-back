package com.five9th.smagent.platform.adapter.linkedin

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
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * LinkedIn posting as the authenticated member (personal profile) via the
 * versioned Posts API (`POST /rest/posts`, needs the w_member_social scope).
 *
 * Credentials: { "accessToken": "..." } — a member token from the OAuth2
 * authorization-code flow, ideally with the scopes
 * `openid profile w_member_social`: openid+profile let verify() resolve the
 * author and display name via GET /v2/userinfo, a Share-on-LinkedIn-only token
 * still posts (see [member]). Member tokens expire after ~60 days and need
 * re-pasting.
 *
 * Like Facebook, LinkedIn has no title: command.title is ignored. A post link
 * is appended to the commentary on its own line (the Posts API grows a real
 * link card only with article/media content — a later iteration).
 */
@Component
class LinkedInAccessTokenAdapter(
    props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : SocialPlatformAdapter {

    override val platform = Platform.LINKEDIN
    override val authMethod = AuthMethod.ACCESS_TOKEN
    override val credentialFields = mapOf("accessToken" to CredentialFieldKind.TOKEN)
    override val canPublish = true

    private val apiVersion = props.linkedin.apiVersion
    private val client = restClientBuilder.baseUrl(props.linkedin.baseUrl).build()
    private val mapper = JsonMapper.builder().build()

    override fun verify(credentialsJson: String): VerifyResult {
        val member = member(accessToken(credentialsJson))
            ?: return VerifyResult(displayName = null) // live token, no openid scope: keep the label
        return VerifyResult(displayName = member.name ?: member.id)
    }

    override fun publish(credentialsJson: String, command: PublishCommand): PublishResult {
        val token = accessToken(credentialsJson)
        val author = "urn:li:person:" + (member(token)?.id ?: ACCESS_TOKEN_SUBJECT)
        val commentary = command.text +
            (command.link?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\n$it" } ?: "")

        val response: ResponseEntity<String> = try {
            client.post()
                .uri("/rest/posts")
                .headers { h ->
                    h.setBearerAuth(token)
                    h.set("X-Restli-Protocol-Version", RESTLI_PROTOCOL_VERSION)
                    h.set("LinkedIn-Version", apiVersion)
                }
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "author" to author,
                            "commentary" to commentary,
                            "visibility" to "PUBLIC",
                            "distribution" to mapOf(
                                "feedDistribution" to "MAIN_FEED",
                                "targetEntities" to emptyList<String>(),
                                "thirdPartyDistributionChannels" to emptyList<String>(),
                            ),
                            "lifecycleState" to "PUBLISHED",
                            "isReshareDisabledByAuthor" to false,
                        )
                    )
                )
                .retrieve()
                .toEntity(String::class.java)
        } catch (e: PlatformException) {
            throw e
        } catch (e: RestClientResponseException) {
            throw apiError(e)
        } catch (e: Exception) {
            throw PlatformException("LinkedIn API call failed: ${e.message}", e)
        }

        // 201 with an empty body; the created post's URN is in this header
        val postUrn = response.headers.getFirst("x-restli-id")
            ?: throw PlatformException("LinkedIn publish response did not include the post id header")
        return PublishResult(externalUrl = "https://www.linkedin.com/feed/update/$postUrn")
    }

    private fun accessToken(credentialsJson: String): String =
        CredentialParser.parse(credentialsJson, *credentialFields.keys.toTypedArray()).getValue("accessToken")

    private data class Member(val id: String, val name: String?)

    /**
     * The token's member via OpenID Connect (GET /v2/userinfo): `sub` is the
     * person id the author URN needs. Null when the token is live but lacks
     * the openid scope (403) — such a token can still post, its author is
     * resolved server-side from the {accessTokenSubject} placeholder.
     */
    private fun member(token: String): Member? = try {
        val body: JsonNode = mapper.readTree(
            client.get()
                .uri("/v2/userinfo")
                .headers { it.setBearerAuth(token) }
                .retrieve()
                .body(String::class.java) ?: ""
        )
        Member(
            id = body.path("sub").asStringOrNull()
                ?: throw PlatformException("LinkedIn userinfo response did not include a member id"),
            name = body.path("name").asStringOrNull(),
        )
    } catch (e: RestClientResponseException) {
        if (e.statusCode.value() == 403) null else throw apiError(e)
    } catch (e: PlatformException) {
        throw e
    } catch (e: Exception) {
        throw PlatformException("LinkedIn API call failed: ${e.message}", e)
    }

    private fun apiError(e: RestClientResponseException): PlatformException {
        val hint = if (e.statusCode.value() == 401) {
            " (invalid or expired access token — member tokens last ~60 days)"
        } else {
            ""
        }
        return PlatformException("LinkedIn API error (${e.statusCode.value()}): ${extractErrorMessage(e)}$hint", e)
    }

    private fun extractErrorMessage(e: RestClientResponseException): String = try {
        val root = mapper.readTree(e.responseBodyAsString)
        root.path("message").asStringOrNull()
            ?: root.path("error_description").asStringOrNull()
            ?: e.statusText
    } catch (_: Exception) {
        e.statusText
    }

    private fun JsonNode.asStringOrNull(): String? =
        if (isNull || isMissingNode) null else asString()

    companion object {
        private const val RESTLI_PROTOCOL_VERSION = "2.0.0"
        private const val ACCESS_TOKEN_SUBJECT = "{accessTokenSubject}"
    }
}
