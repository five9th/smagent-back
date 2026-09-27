package com.five9th.smagent.domain

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyEnumerated
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "posts")
class Post(
    /** Self-post content; title is required when publishing to Reddit. */
    @Embedded
    var content: PostContent = PostContent(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PostStatus = PostStatus.DRAFT,

    /** Per-platform replacement content (character limits, per-platform titles). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "post_overrides", joinColumns = [JoinColumn(name = "post_id")])
    @MapKeyEnumerated(EnumType.STRING)
    var overrides: MutableMap<Platform, PostContent> = mutableMapOf(),

    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
