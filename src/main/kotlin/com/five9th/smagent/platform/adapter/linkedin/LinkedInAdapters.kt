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

/** Strips an optional "urn:li:organization:" prefix; both paste styles work. */
internal fun normalizeOrganizationId(id: String): String =
    id.trim().removePrefix("urn:li:organization:")

private const val RESTLI_PROTOCOL_VERSION = "2.0.0"

/**
 * Shared plumbing for the LinkedIn adapters, which all post text through the
 * versioned Posts API (`POST /rest/posts`: commentary + an optional link
 * appended on its own line — no title, a real link card needs article/media
 * content, a later iteration). Subclasses decide the `author` URN; LinkedIn
 * member/organization tokens both expire after ~60 days and need re-pasting.
 */
abstract class LinkedInRestAdapter(
    props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : SocialPlatformAdapter {

    final override val platform = Platform.LINKEDIN
    final override val canPublish = true

    protected val apiVersion = props.linkedin.apiVersion
    protected val client = restClientBuilder.baseUrl(props.linkedin.baseUrl).build()
    protected val mapper = JsonMapper.builder().build()

    /** The `author` URN of POST /rest/posts for this login option. */
    protected abstract fun author(credentials: Map<String, String>): String
    final override fun publish(credentialsJson: String, command: PublishCommand): PublishResult {
        val creds = CredentialParser.parse(credentialsJson, *credentialFields.keys.toTypedArray())
        val commentary = command.text +
            (command.link?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\n$it" } ?: "")

        val response: ResponseEntity<String> = try {
            client.post()
                .uri("/rest/posts")
                .headers { h ->
                    h.setBearerAuth(creds.getValue("accessToken"))
                    h.set("X-Restli-Protocol-Version", RESTLI_PROTOCOL_VERSION)
                    h.set("LinkedIn-Version", apiVersion)
                }
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "author" to author(creds),
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

    protected fun apiError(e: RestClientResponseException): PlatformException {
        val hint = if (e.statusCode.value() == 401) {
            " (invalid or expired access token — LinkedIn tokens last ~60 days)"
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

    protected fun JsonNode.asStringOrNull(): String? =
        if (isNull || isMissingNode) null else asString()
}

/**
 * LinkedIn posting as the authenticated member (personal profile)
 * (needs the w_member_social scope).
 *
 * Credentials: { "accessToken": "..." } — a member token from the OAuth2
 * authorization-code flow, ideally with the scopes
 * `openid profile w_member_social`: openid+profile let verify() resolve the
 * author and display name via GET /v2/userinfo, a Share-on-LinkedIn-only token
 * still posts (see [member]). Member tokens expire after ~60 days and need
 * re-pasting.
 */
@Component
class LinkedInAccessTokenAdapter(
    props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : LinkedInRestAdapter(props, restClientBuilder) {

    override val authMethod = AuthMethod.ACCESS_TOKEN
    override val credentialFields = mapOf("accessToken" to CredentialFieldKind.TOKEN)

    override fun verify(credentialsJson: String): VerifyResult {
        val member = member(CredentialParser.parse(credentialsJson, "accessToken").getValue("accessToken"))
            ?: return VerifyResult(displayName = null) // live token, no openid scope: keep the label
        return VerifyResult(displayName = member.name ?: member.id)
    }

    override fun author(credentials: Map<String, String>): String =
        "urn:li:person:" + (member(credentials.getValue("accessToken"))?.id ?: ACCESS_TOKEN_SUBJECT)

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

    companion object {
        private const val ACCESS_TOKEN_SUBJECT = "{accessTokenSubject}"
    }
}

/**
 * LinkedIn posting as a company page (organization) (needs the
 * w_organization_social scope and a page role — ADMINISTRATOR or
 * CONTENT_ADMIN — for the authenticated member).
 *
 * Credentials: { "organizationId": "...", "accessToken": "..." } — the
 * numeric company id (a full `urn:li:organization:...` URN pastes too).
 * verify() reads the page name via GET /rest/organizations/{id}, which
 * additionally wants the rw_organization_admin scope and an ADMINISTRATOR
 * role; without them a live token still verifies namelessly (403) — the
 * first real post is the test.
 */
@Component
class LinkedInOrganizationAccessTokenAdapter(
    props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : LinkedInRestAdapter(props, restClientBuilder) {

    override val authMethod = AuthMethod.ORGANIZATION_ACCESS_TOKEN
    override val credentialFields = mapOf(
        "organizationId" to CredentialFieldKind.ID,
        "accessToken" to CredentialFieldKind.TOKEN,
    )

    override fun verify(credentialsJson: String): VerifyResult {
        val creds = CredentialParser.parse(credentialsJson, *credentialFields.keys.toTypedArray())
        val id = normalizeOrganizationId(creds.getValue("organizationId"))
        val body: JsonNode = try {
            mapper.readTree(
                client.get()
                    .uri("/rest/organizations/$id")
                    .headers { h ->
                        h.setBearerAuth(creds.getValue("accessToken"))
                        h.set("X-Restli-Protocol-Version", RESTLI_PROTOCOL_VERSION)
                        h.set("LinkedIn-Version", apiVersion)
                    }
                    .retrieve()
                    .body(String::class.java) ?: ""
            )
        } catch (e: RestClientResponseException) {
            // 403 = the token is live but can't read the page (missing page
            // role or rw_organization_admin scope) — posting may still work
            if (e.statusCode.value() == 403) return VerifyResult(displayName = null)
            throw apiError(e)
        } catch (e: Exception) {
            throw PlatformException("LinkedIn API call failed: ${e.message}", e)
        }
        val name = body.path("localizedName").asStringOrNull()
            ?: throw PlatformException("LinkedIn verify response did not include an organization name")
        return VerifyResult(displayName = name)
    }

    override fun author(credentials: Map<String, String>): String =
        "urn:li:organization:" + normalizeOrganizationId(credentials.getValue("organizationId"))
}
