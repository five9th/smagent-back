package com.five9th.smagent.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Demo-only feedback list item; standalone, no relations, no soft delete. */
@Entity
@Table(name = "feedback_items")
class FeedbackItem(
    @Column(nullable = false, length = 2000)
    var text: String,

    var checked: Boolean = false,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
