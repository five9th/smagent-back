package com.five9th.smagent.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "publications")
class Publication(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    var post: Post,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "social_account_id", nullable = false)
    var socialAccount: SocialAccount,

    /** Publish-time snapshots; survive account rename/soft-delete. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var platform: Platform,

    @Column(nullable = false, length = 200)
    var accountName: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PublicationStatus = PublicationStatus.PENDING,

    @Column(length = 2048)
    var externalUrl: String? = null,

    @Column(length = 2000)
    var errorMessage: String? = null,

    var publishedAt: Instant? = null,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
