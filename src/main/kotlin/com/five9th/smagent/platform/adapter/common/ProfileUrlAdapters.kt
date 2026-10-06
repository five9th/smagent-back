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
import org.springframework.stereotype.Component
import java.net.URI

/**
 * PROFILE_URL login option: the account is nothing but a link to the
 * profile/page. It gives the app no platform API access — the point is that
 * the platform is usable for manual posting until a real login option is
 * implemented for it. Every platform gets one subclass.
 *
 * Credentials: { "profileUrl": "https://..." }
 */
abstract class ProfileUrlAdapter(
    override val platform: Platform,
) : SocialPlatformAdapter {

    final override val authMethod = AuthMethod.PROFILE_URL
    final override val credentialFields = mapOf(PROFILE_URL_FIELD to CredentialFieldKind.ID) // the link is the point
    final override val canPublish = false

    /** No platform API involved — only the URL shape is checked. */
    final override fun verify(credentialsJson: String): VerifyResult {
        profileUrl(credentialsJson)
        return VerifyResult(displayName = null) // keep the user-set label
    }

    final override fun publish(credentialsJson: String, command: PublishCommand): PublishResult =
        throw PlatformException(
            "Manual login option: open ${profileUrl(credentialsJson)} and create the post there"
        )

    private fun profileUrl(credentialsJson: String): String {
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

    companion object {
        const val PROFILE_URL_FIELD = "profileUrl"
        private val HTTP_SCHEMES = setOf("http", "https")
    }
}

@Component
class FacebookProfileUrlAdapter : ProfileUrlAdapter(Platform.FACEBOOK)

@Component
class RedditProfileUrlAdapter : ProfileUrlAdapter(Platform.REDDIT)

@Component
class XProfileUrlAdapter : ProfileUrlAdapter(Platform.X)

@Component
class LinkedInProfileUrlAdapter : ProfileUrlAdapter(Platform.LINKEDIN)
