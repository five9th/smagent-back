package com.five9th.smagent.domain

enum class Platform { FACEBOOK, REDDIT }

enum class Role { OWNER }

/** Lifecycle of a post. Scheduling may add SCHEDULED later. */
enum class PostStatus { DRAFT, PARTIALLY_PUBLISHED, PUBLISHED }

/** One row per post x account publish attempt. */
enum class PublicationStatus { PENDING, SUCCESS, FAILED }

/** Result of the last credential verification per account. */
enum class AccountStatus { UNVERIFIED, ACTIVE, INVALID }
