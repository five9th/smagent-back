package com.five9th.smagent.post

import com.five9th.smagent.domain.Platform
import com.five9th.smagent.domain.PostStatus
import com.five9th.smagent.domain.PublicationStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.Instant

data class CreatePostRequest(
    /** Post content; Reddit additionally requires a title at publish time. */
    @field:Valid val content: PostDto.PostContentDto,
    /** Per-platform replacement content (character limits, per-platform titles). */
    val overrides: Map<Platform, PostDto.PostContentDto>? = null,
)

data class UpdatePostRequest(
    /** When present, replaces title and text wholesale (null title clears it). */
    @field:Valid val content: PostDto.PostContentDto? = null,
    /** Replaced wholesale when present. */
    val overrides: Map<Platform, PostDto.PostContentDto>? = null,
)

data class PostDto(
    val id: Long?,
    val content: PostContentDto,
    val status: PostStatus,
    val overrides: Map<Platform, PostContentDto>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val publications: List<PublicationDto> = emptyList(),
) {
    data class PostContentDto(
        val title: String?,
        @field:NotBlank val text: String,
        /** Optional http(s) link the post shares. */
        val link: String? = null,
    )
}

data class PublicationDto(
    val id: Long?,
    val postId: Long?,
    val accountId: Long?,
    val platform: Platform,
    val accountName: String,
    val status: PublicationStatus,
    val publishedManually: Boolean,
    val externalUrl: String?,
    val errorMessage: String?,
    val publishedAt: Instant?,
    val createdAt: Instant,
)

data class PublishRequest(
    @field:NotEmpty val accountIds: List<Long>,
)

data class ManualPublishRequest(
    @field:NotNull val accountId: Long,
    /** Optional link to the by-hand post, kept in the history. */
    val externalUrl: String? = null,
)

data class PublishOutcome(
    val accountId: Long?,
    val platform: Platform,
    val accountName: String,
    val status: PublicationStatus,
    val publishedManually: Boolean,
    val externalUrl: String?,
    val errorMessage: String?,
)

data class PublishResponse(
    val postId: Long?,
    val postStatus: PostStatus,
    val results: List<PublishOutcome>,
)

/** GET /api/posts/{id}/share-url — the prefilled composer URL for a manual account. */
data class ShareUrlResponse(
    val url: String,
)
