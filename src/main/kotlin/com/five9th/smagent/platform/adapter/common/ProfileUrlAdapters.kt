package com.five9th.smagent.platform.adapter.common

import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.CredentialFieldKind
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.platform.CredentialParser
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.platform.PublishCommand
import com.five9th.smagent.platform.PublishResult
import com.five9th.smagent.platform.SocialPlatformAdapter
import com.five9th.smagent.platform.VerifyResult
import com.five9th.smagent.platform.adapter.reddit.normalizeSubreddit
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val PROFILE_URL_FIELD = "profileUrl"
private const val SUBREDDIT_FIELD = "subreddit"
private val HTTP_SCHEMES = setOf("http", "https")

/**
 * PROFILE_URL login option: the account is a link to the profile/page plus
 * whatever target info the platform's composer needs (Reddit: subreddit).
 * No platform API access — the SPA opens [shareUrl] (a prefilled composer) or
 * the stored profile link, the user posts by hand, and the result is recorded
 * via publish-manual.
 */
abstract class ProfileUrlAdapter(
    override val platform: Platform,
) : SocialPlatformAdapter {

    final override val authMethod = AuthMethod.PROFILE_URL
    final override val canPublish = false
    final override val canShare = true

    /** No platform API involved — only the stored fields' shape is checked. */
    final override fun verify(credentialsJson: String): VerifyResult {
        profileUrl(credentialsJson)
        return VerifyResult(displayName = null) // keep the user-set label
    }

    final override fun publish(credentialsJson: String, command: PublishCommand): PublishResult {
        // the prefilled composer beats the bare profile link when it can be built
        val url = shareUrl(credentialsJson, command) ?: profileUrl(credentialsJson)
        throw PlatformException("Manual login option: open $url and finish the post there")
    }

    protected fun profileUrl(credentialsJson: String): String {
        val url = CredentialParser.parse(credentialsJson, PROFILE_URL_FIELD).getValue(PROFILE_URL_FIELD)
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw PlatformException("profileUrl is not a valid URL: '$url'", e)
        }
        if (uri.scheme !in HTTP_SCHEMES || uri.host.isNullOrBlank()) {
            throw PlatformException("profileUrl must be an http(s) URL: '$url'")
        }
        return url
    }

    /** base + "?k=v&..." built from the non-blank params, URL-encoded. */
    protected fun composeUrl(base: String, vararg params: Pair<String, String?>): String {
        val query = params.mapNotNull { (name, value) ->
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { "$name=${encode(it)}" }
        }
        return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

/** Shares the post link via sharer.php — no app id, link-only prefill. */
@Component
class FacebookProfileUrlAdapter : ProfileUrlAdapter(Platform.FACEBOOK) {
    override val credentialFields = mapOf(PROFILE_URL_FIELD to CredentialFieldKind.ID)
    override fun shareUrl(credentialsJson: String, command: PublishCommand): String =
        composeUrl("https://www.facebook.com/sharer/sharer.php", "u" to command.link)
}

/** Web intent — the only platform that prefills the text. */
@Component
class XProfileUrlAdapter : ProfileUrlAdapter(Platform.X) {
    override val credentialFields = mapOf(PROFILE_URL_FIELD to CredentialFieldKind.ID)
    override fun shareUrl(credentialsJson: String, command: PublishCommand): String =
        composeUrl("https://x.com/intent/tweet", "text" to command.text, "url" to command.link)
}

/** Link-only share; the composer opens even without a link. */
@Component
class LinkedInProfileUrlAdapter : ProfileUrlAdapter(Platform.LINKEDIN) {
    override val credentialFields = mapOf(PROFILE_URL_FIELD to CredentialFieldKind.ID)
    override fun shareUrl(credentialsJson: String, command: PublishCommand): String =
        composeUrl("https://www.linkedin.com/sharing/share-offsite/", "url" to command.link)
}

/** r/<subreddit>/submit with title+link prefilled (a text body can't be prefilled). */
@Component
class RedditProfileUrlAdapter : ProfileUrlAdapter(Platform.REDDIT) {
    override val credentialFields = mapOf(
        PROFILE_URL_FIELD to CredentialFieldKind.ID,
        SUBREDDIT_FIELD to CredentialFieldKind.ID,
    )
    override fun shareUrl(credentialsJson: String, command: PublishCommand): String {
        val sub = normalizeSubreddit(
            CredentialParser.parse(credentialsJson, SUBREDDIT_FIELD).getValue(SUBREDDIT_FIELD)
        )
        return composeUrl(
            "https://www.reddit.com/r/$sub/submit",
            "url" to command.link,
            "title" to command.title,
        )
    }
}
