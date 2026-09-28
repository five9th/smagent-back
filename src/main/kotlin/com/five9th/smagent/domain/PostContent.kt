package com.five9th.smagent.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * Content unit (title + text); embedded into [Post] and used for per-platform
 * overrides. Media fields will be added here later.
 */
@Embeddable
data class PostContent(
    @Column(length = 300)
    var title: String? = null,

    // left nullable: ddl-auto=update cannot add a NOT NULL column to a table
    // that already has rows (post_overrides); blank-ness is enforced in the
    // service layer instead
    // TODO: make it not nullable
    @Column(length = 5000)
    var text: String = "",
)
