package com.five9th.smagent.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "social_accounts")
class SocialAccount(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var platform: Platform,

    /** Login option this account authenticates with (adapter dispatch key). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var authMethod: AuthMethod,

    /** Human label; refreshed by the verify endpoint (page name / u_name). */
    @Column(nullable = false, length = 200)
    var displayName: String = "",

    /**
     * AES-GCM encrypted, platform-specific JSON credential blob
     * (see platform/Credentials.kt). Never returned by the API.
     */
    @Column(nullable = false, length = 4096)
    var credentialsEnc: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AccountStatus = AccountStatus.UNVERIFIED,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    /** Soft delete marker; null = live. Credentials are blanked on delete. */
    var deletedAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
