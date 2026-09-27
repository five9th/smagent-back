package com.five9th.smagent.post

import com.five9th.smagent.common.NotFoundException
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

    fun list(status: PostStatus?): List<PostDto> {
        val all = when (status) {
            null -> posts.findAllByOrderByCreatedAtDesc()
            else -> posts.findByStatusOrderByCreatedAtDesc(status)
        }
        return all.map { toDto(it, includePublications = false) }
    }

    fun getDto(id: Long): PostDto = toDto(get(id), includePublications = true)

    fun get(id: Long): Post = posts.findById(id)
        .orElseThrow { NotFoundException("Post $id not found") }

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

    fun delete(id: Long) {
        val post = get(id)
        publications.deleteAll(publications.findByPostId(id))
        posts.delete(post)
    }

    fun markPublishedIfAnySuccess(post: Post) {
        val anySuccess = publications.findByPostId(post.id!!).any { it.status == PublicationStatus.SUCCESS }
        if (anySuccess && post.status != PostStatus.PUBLISHED) {
            post.status = PostStatus.PUBLISHED
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
