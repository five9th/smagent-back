package com.five9th.smagent.platform

import com.five9th.smagent.domain.Platform

/**
 * One implementation per supported platform. Dispatch is by [platform];
 * Spring injects all implementations as a list.
 *
 * Credential handling: adapters receive the decrypted credential JSON string
 * exactly as the client submitted it and parse their own typed shape.
 */
interface SocialPlatformAdapter {
    val platform: Platform

    /** Fields that must be present in the credential JSON (validated on create/update). */
    val requiredCredentialFields: Set<String>

    /** Cheap "who am I" call; returns the account's display name on success. */
    fun verify(credentialsJson: String): VerifyResult

    /** Publish text; returns the URL of the created post. */
    fun publish(credentialsJson: String, command: PublishCommand): PublishResult
}

data class VerifyResult(val displayName: String)

data class PublishCommand(
    /** Self-post title; Reddit requires it, Facebook ignores it. */
    val title: String?,
    val text: String,
)

data class PublishResult(val externalUrl: String)

/** Upstream platform API failure (bad credentials, rejected post, outage...). */
class PlatformException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
