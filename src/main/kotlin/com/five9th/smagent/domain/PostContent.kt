package com.five9th.smagent.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * Content unit (title + text). Used for per-platform overrides so each platform
 * can vary both. Media fields will be added here later.
 */
@Embeddable
data class PostContent(
    @Column(name = "override_title", length = 300)
    var title: String? = null,

    // nullable column: ddl-auto=update cannot add a NOT NULL column to a table
    // that already has rows; blank-ness is enforced in the service layer instead
    @Column(name = "override_text", length = 5000)
    var text: String = "",
)
