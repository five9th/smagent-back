package com.five9th.smagent.platform

import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.CredentialFieldKind
import com.five9th.smagent.domain.Platform

/**
 * One implementation per (platform, authMethod) login option; Spring injects
 * all implementations as a list (see AdapterRegistry for dispatch).
 *
 * Credential handling: adapters receive the decrypted credential JSON string
 * exactly as the client submitted it and parse their own typed shape.
 */
interface SocialPlatformAdapter {
    val platform: Platform

    /** Which login option of [platform] this adapter implements. */
    val authMethod: AuthMethod

    /**
     * Credential fields this login option declares (validated on create/update),
     * mapped to how each is rendered in `credentialsMasked`: ID in full, TOKEN
     * keeps its last 4 characters, PASSWORD is fully masked. Undeclared fields
     * default to PASSWORD — secrets fail closed.
     */
    val credentialFields: Map<String, CredentialFieldKind>

    /**
     * Declared fields the user may leave blank — saved as "" instead of failing
     * validation (e.g. LinkedIn's userId, which merely pins the post author).
     * Everything in [credentialFields] not listed here is required.
     */
    val credentialsMayBeBlank: Set<String> get() = emptySet()

    /** False when the login option gives no platform API access (PROFILE_URL). */
    val canPublish: Boolean

    /** True when the login option can compose a prefilled composer/share URL. */
    val canShare: Boolean get() = false

    /** Cheap "who am I" call; a null display name keeps the account's current one. */
    fun verify(credentialsJson: String): VerifyResult

    /** Publish text; returns the URL of the created post. */
    fun publish(credentialsJson: String, command: PublishCommand): PublishResult

    /**
     * Platform composer/share URL prefilled with the content, for manual login
     * options; null when this option can't compose one.
     */
    fun shareUrl(credentialsJson: String, command: PublishCommand): String? = null
}

/**
 * Credential rotation: adapters whose platform rotates tokens on use (X
 * refresh tokens are single-use) return the freshly issued values here — on
 * the success results AND on [PlatformException] (a call that refreshed fine
 * but then failed still rotated the stored refresh token). Callers must
 * persist the returned fields into the account or it bricks on the next call.
 */
data class VerifyResult(
    val displayName: String?,
    val refreshedCredentials: Map<String, String>? = null,
)

data class PublishCommand(
    /** Self-post title; Reddit requires it, Facebook ignores it. */
    val title: String?,
    val text: String,
    /** Optional link the post shares; feeds share/composer URLs. */
    val link: String? = null,
)

data class PublishResult(
    val externalUrl: String,
    val refreshedCredentials: Map<String, String>? = null,
)

/**
 * Upstream platform API failure (bad credentials, rejected post, outage...).
 * [refreshedCredentials] carries rotated token values the caller must persist
 * even though the call failed (see [VerifyResult]).
 */
class PlatformException(
    message: String,
    cause: Throwable? = null,
    val refreshedCredentials: Map<String, String>? = null,
) : RuntimeException(message, cause)
