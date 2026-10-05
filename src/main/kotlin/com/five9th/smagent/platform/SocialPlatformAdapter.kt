package com.five9th.smagent.platform

import com.five9th.smagent.domain.AuthMethod
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

    /** Fields that must be present in the credential JSON (validated on create/update). */
    val requiredCredentialFields: Set<String>

    /**
     * Credential fields that identify the target rather than authenticate it
     * (page IDs, usernames, URLs) — shown in full in `credentialsMasked`.
     * Everything else, including unknown fields, is masked: secrets fail closed.
     */
    val nonSecretCredentialFields: Set<String>

    /** False when the login option gives no platform API access (PROFILE_URL). */
    val canPublish: Boolean

    /** Cheap "who am I" call; a null display name keeps the account's current one. */
    fun verify(credentialsJson: String): VerifyResult

    /** Publish text; returns the URL of the created post. */
    fun publish(credentialsJson: String, command: PublishCommand): PublishResult
}

data class VerifyResult(val displayName: String?)

data class PublishCommand(
    /** Self-post title; Reddit requires it, Facebook ignores it. */
    val title: String?,
    val text: String,
)

data class PublishResult(val externalUrl: String)

/** Upstream platform API failure (bad credentials, rejected post, outage...). */
class PlatformException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
