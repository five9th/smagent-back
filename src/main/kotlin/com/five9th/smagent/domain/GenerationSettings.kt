package com.five9th.smagent.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Singleton row (id asc) holding the owner's generation preferences: a JSON
 * map GeneratePlatform -> style override for the AI system prompt. Missing
 * keys fall back to the built-in defaults (see GenerationContract).
 */
@Entity
@Table(name = "generation_settings")
class GenerationSettings(
    /** JSON object: { "<GeneratePlatform>": "<style text>", ... } — overrides only, never blank values. */
    @Column(nullable = false, length = 8000)
    var rulesJson: String = "{}",
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
