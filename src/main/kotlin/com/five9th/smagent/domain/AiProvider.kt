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
@Table(name = "ai_providers")
class AiProvider(
    /** Human label shown in the SPA and echoed by generation responses. */
    @Column(nullable = false, length = 200)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: AiProviderType = AiProviderType.ANTHROPIC,

    /**
     * Optional API root override (origin only — the SDK appends /v1/...),
     * e.g. an Anthropic-compatible gateway. Null = the provider's own default.
     */
    @Column(length = 2048)
    var baseUrl: String? = null,

    /** AES-GCM encrypted API key; never returned (responses carry apiKeyMasked). */
    @Column(nullable = false, length = 4096)
    var apiKeyEnc: String = "",

    /** Model for generation requests that don't name one; null = built-in default. */
    @Column(length = 200)
    var defaultModel: String? = null,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
