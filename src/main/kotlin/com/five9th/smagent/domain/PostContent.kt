package com.five9th.smagent.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * Content unit (title + text + optional link); embedded into [Post] and used
 * for per-platform overrides. Media fields will be added here later.
 */
@Embeddable
data class PostContent(
    @Column(length = 300)
    var title: String? = null,

    // blank-ness is enforced in the service layer; NOT NULL guards the schema
    @Column(nullable = false, length = 5000)
    var text: String = "",

    /** Optional http(s) link the post shares — feeds manual share/composer URLs. */
    @Column(length = 2048)
    var link: String? = null,
)
