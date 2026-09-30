package com.five9th.smagent.post

import com.five9th.smagent.common.NotFoundException
import com.five9th.smagent.common.NotImplementedException
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.domain.Post
import com.five9th.smagent.domain.PostContent
import com.five9th.smagent.domain.PostStatus
import com.five9th.smagent.domain.PublicationStatus
import com.five9th.smagent.post.PostDto.PostContentDto
import com.five9th.smagent.repo.PostRepository
import com.five9th.smagent.repo.PublicationRepository
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PostService(
    private val posts: PostRepository,
    private val publications: PublicationRepository,
) {

    fun list(status: PostStatus?, showDeleted: Boolean = false): List<PostDto> {
        val all = when {
            showDeleted && status != null -> posts.findByStatusOrderByCreatedAtDesc(status)
            showDeleted -> posts.findAllByOrderByCreatedAtDesc()
            status != null -> posts.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(status)
            else -> posts.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
        }
        return all.map { toDto(it, includePublications = false) }
    }

    fun getDto(id: Long): PostDto = toDto(get(id), includePublications = true)

    /** Soft-deleted posts behave as missing (404) everywhere. */
    fun get(id: Long): Post = posts.findByIdAndDeletedAtIsNull(id)
        ?: throw NotFoundException("Post $id not found")

    fun create(request: CreatePostRequest): PostDto {
        validateOverrides(request.overrides)
        val post = Post(
            content = PostContent(
                title = normalizedTitle(request.content.title),
                text = request.content.text.trim(),
            ),
            overrides = request.overrides.toContentMap(),
        )
        return toDto(posts.save(post), includePublications = false)
    }

    fun update(id: Long, request: UpdatePostRequest): PostDto {
        val post = get(id)
        if (post.status != PostStatus.DRAFT) {
            throw IllegalArgumentException("Only DRAFT posts can be edited (post $id is ${post.status})")
        }
        request.content?.let {
            post.content = PostContent(title = normalizedTitle(it.title), text = it.text.trim())
        }
        request.overrides?.let {
            validateOverrides(it)
            post.overrides = it.toContentMap()
        }
        post.updatedAt = Instant.now()
        return toDto(posts.save(post), includePublications = true)
    }

    /**
     * Soft delete; publications are kept as history.
     *
     * `deletePublications` (whether to also delete the post from the platforms) is
     * only meaningful for PUBLISHED posts: for those it must be stated explicitly
     * (400 otherwise); `true` is not implemented yet (501). DRAFT posts ignore it.
     */
    fun delete(id: Long, deletePublications: Boolean?) {
        val post = get(id)
        if (post.status == PostStatus.PUBLISHED || post.status == PostStatus.PARTIALLY_PUBLISHED) { // copies exist on platforms
            if (deletePublications == null) {
                throw IllegalArgumentException(
                    "Post $id is PUBLISHED. State explicitly what happens to its platform posts: " +
                        "pass deletePublications=false (local delete only) or true (also delete on platforms — not implemented yet)."
                )
            }
            if (deletePublications) {
                throw NotImplementedException("Deleting published posts from platforms is not implemented yet")
            }
        }
        post.deletedAt = Instant.now()
        posts.save(post)
    }

    /**
     * Post-publish status refresh, judged over the CURRENT publish round only
     * (so a republish that fixes earlier failures upgrades the status):
     * every attempt SUCCESS -> PUBLISHED; some -> PARTIALLY_PUBLISHED; none -> DRAFT.
     */
    fun refreshPostStatus(post: Post, roundStatuses: List<PublicationStatus>) {
        val status = when {
            roundStatuses.isNotEmpty() && roundStatuses.all { it == PublicationStatus.SUCCESS } ->
                PostStatus.PUBLISHED
            roundStatuses.any { it == PublicationStatus.SUCCESS } ->
                PostStatus.PARTIALLY_PUBLISHED
            else -> null // stays DRAFT
        }
        if (status != null && post.status != status) {
            post.status = status
            post.updatedAt = Instant.now()
            posts.save(post)
        }
    }

    fun publicationsOf(postId: Long): List<PublicationDto> =
        publications.findByPostIdOrderByCreatedAtDesc(postId).map { PublicationMapper.toDto(it) }

    private fun toDto(post: Post, includePublications: Boolean): PostDto = PostDto(
        id = post.id,
        content = PostContentDto(title = post.content.title, text = post.content.text),
        status = post.status,
        overrides = post.overrides.mapValues { PostContentDto(title = it.value.title, text = it.value.text) },
        createdAt = post.createdAt,
        updatedAt = post.updatedAt,
        deletedAt = post.deletedAt,
        publications = if (includePublications && post.id != null) {
            val postId = post.id!!
            publications.findByPostIdOrderByCreatedAtDesc(postId).map { PublicationMapper.toDto(it) }
        } else {
            emptyList()
        },
    )

    private fun normalizedTitle(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() }

    /** Map values aren't reached by bean validation (@Valid doesn't cascade into plain map values). */
    private fun validateOverrides(overrides: Map<Platform, PostContentDto>?) {
        overrides?.forEach { (platform, content) ->
            if (content.text.isBlank()) {
                throw IllegalArgumentException("Override content for $platform must include text")
            }
        }
    }

    private fun Map<Platform, PostContentDto>?.toContentMap(): MutableMap<Platform, PostContent> =
        orEmpty().mapValues { PostContent(title = normalizedTitle(it.value.title), text = it.value.text.trim()) }
            .toMutableMap()
}
