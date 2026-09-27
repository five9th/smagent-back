package com.five9th.smagent.post

import com.five9th.smagent.domain.Platform
import com.five9th.smagent.domain.PostStatus
import com.five9th.smagent.domain.PublicationStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.Instant

data class CreatePostRequest(
    /** Optional; Reddit requires it at publish time. */
    val title: String? = null,
    @field:NotBlank val text: String,
    /** Per-platform replacement text, e.g. for character limits. */
    val overrides: Map<Platform, String>? = null,
)

data class UpdatePostRequest(
    val title: String? = null,
    val text: String? = null,
    val overrides: Map<Platform, String>? = null,
)

// TODO: update everything that uses it (and docs)
data class PostDto(
    val id: Long?,
    val content: PostContentDto,
    val status: PostStatus,
    val overrides: Map<Platform, PostContentDto>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val publications: List<PublicationDto> = emptyList(),
) {
    data class PostContentDto(
        val title: String?,
        val text: String,
        // other content (e.g. images) may be added here later
    )
}

data class PublicationDto(
    val id: Long?,
    val postId: Long?,
    val accountId: Long?,
    val platform: Platform,
    val accountName: String,
    val status: PublicationStatus,
    val externalUrl: String?,
    val errorMessage: String?,
    val publishedAt: Instant?,
    val createdAt: Instant,
)

data class PublishRequest(
    @field:NotEmpty val accountIds: List<Long>,
)

data class PublishOutcome(
    val accountId: Long?,
    val platform: Platform,
    val accountName: String,
    val status: PublicationStatus,
    val externalUrl: String?,
    val errorMessage: String?,
)

data class PublishResponse(
    val postId: Long?,
    val postStatus: PostStatus,
    val results: List<PublishOutcome>,
)
