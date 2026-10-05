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

    /** Any platform: just a profile/page link, no API access (manual posting). */
    PROFILE_URL,
}

enum class Role { OWNER }

/** Lifecycle of a post. Scheduling may add SCHEDULED later. */
enum class PostStatus { DRAFT, PUBLISHED }

/** One row per post x account publish attempt. */
enum class PublicationStatus { PENDING, SUCCESS, FAILED }

/** Result of the last credential verification per account. */
enum class AccountStatus { UNVERIFIED, ACTIVE, INVALID }
