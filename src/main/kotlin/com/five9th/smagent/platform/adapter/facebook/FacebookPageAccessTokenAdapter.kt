package com.five9th.smagent.platform.adapter.facebook

import com.five9th.smagent.config.AppProperties
import com.five9th.smagent.domain.AuthMethod
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
 * Facebook Page posting via the Graph API.
 *
 * Credentials: { "pageId": "...", "pageAccessToken": "..." }
 * A long-lived Page access token (from a User token with pages_show_list +
 * pages_manage_posts + pages_read_engagement) effectively never expires.
 */
@Component
class FacebookPageAccessTokenAdapter(
    props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : SocialPlatformAdapter {

    override val platform = Platform.FACEBOOK
    override val authMethod = AuthMethod.PAGE_ACCESS_TOKEN
    override val requiredCredentialFields = setOf("pageId", "pageAccessToken")
    override val canPublish = true

    private val graphVersion = props.facebook.graphApiVersion
    private val client = restClientBuilder.baseUrl("https://graph.facebook.com").build()
    private val mapper = JsonMapper.builder().build()

    override fun verify(credentialsJson: String): VerifyResult {
        val creds = CredentialParser.parse(credentialsJson, *requiredCredentialFields.toTypedArray())
        val token = creds.getValue("pageAccessToken")
        val body = call {
            client.get()
                .uri { b ->
                    b.path("/{v}/{pageId}")
                        .queryParam("fields", "name")
                        .queryParam("access_token", token)
                        .build(graphVersion, creds.getValue("pageId"))
                }
                .retrieve()
                .body(String::class.java) ?: ""
        }
        val name = body.path("name").asStringOrNull()
            ?: throw PlatformException("Facebook verify response did not include a page name")
        return VerifyResult(displayName = name)
    }

    override fun publish(credentialsJson: String, command: PublishCommand): PublishResult {
        val creds = CredentialParser.parse(credentialsJson, *requiredCredentialFields.toTypedArray())
        val pageId = creds.getValue("pageId")

        val form = LinkedMultiValueMap<String, String>()
        form.add("message", command.text)
        form.add("access_token", creds.getValue("pageAccessToken"))

        val body = call {
            client.post()
                .uri("/{v}/{pageId}/feed", graphVersion, pageId)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String::class.java) ?: ""
        }

        // Graph returns "<pageId>_<postId>"
        val fullId = body.path("id").asStringOrNull()
            ?: throw PlatformException("Facebook publish response did not include a post id")
        val postId = fullId.substringAfter('_', fullId)
        return PublishResult(externalUrl = "https://www.facebook.com/$pageId/posts/$postId")
    }

    private fun call(block: () -> String): JsonNode = try {
        mapper.readTree(block())
    } catch (e: PlatformException) {
        throw e
    } catch (e: RestClientResponseException) {
        throw PlatformException("Facebook API error (${e.statusCode.value()}): ${extractErrorMessage(e)}", e)
    } catch (e: Exception) {
        throw PlatformException("Facebook API call failed: ${e.message}", e)
    }

    private fun extractErrorMessage(e: RestClientResponseException): String = try {
        mapper.readTree(e.responseBodyAsString).path("error").path("message").asStringOrNull()
            ?: e.statusText
    } catch (_: Exception) {
        e.statusText
    }

    private fun JsonNode.asStringOrNull(): String? =
        if (isNull || isMissingNode) null else asString()
}
