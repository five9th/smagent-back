package com.five9th.smagent.domain

enum class Platform { FACEBOOK, REDDIT, X, LINKEDIN }

/**
 * Login option an account uses with its platform; together with [Platform] it
 * picks the adapter. Every platform offers PROFILE_URL; the rest are added as
 * their adapters are implemented.
 */
enum class AuthMethod {
    /** Facebook long-lived Page access token. */
    PAGE_ACCESS_TOKEN,

    /** Reddit "script"-app OAuth2 password grant. */
    PASSWORD_GRANT,

    /** OAuth2 user access token (LinkedIn member profile, X user). */
    ACCESS_TOKEN,

    /** LinkedIn company page (organization) OAuth2 access token. */
    ORGANIZATION_ACCESS_TOKEN,

    /** Any platform: just a profile/page link, no API access (manual posting). */
    PROFILE_URL,
}

/**
 * How a credential field is rendered in `credentialsMasked`: ID in full,
 * TOKEN keeps its last 4 characters, PASSWORD is fully masked. Undeclared
 * fields are treated as PASSWORD — secrets fail closed.
 */
enum class CredentialFieldKind { ID, TOKEN, PASSWORD }

enum class Role { OWNER }

/** Lifecycle of a post. Scheduling may add SCHEDULED later. */
enum class PostStatus { DRAFT, PUBLISHED }

/** One row per post x account publish attempt. */
enum class PublicationStatus { PENDING, SUCCESS, FAILED }

/** Result of the last credential verification per account. */
enum class AccountStatus { UNVERIFIED, ACTIVE, INVALID }
