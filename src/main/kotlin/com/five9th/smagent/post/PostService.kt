package com.five9th.smagent.post

import com.five9th.smagent.common.NotFoundException
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.domain.Post
import com.five9th.smagent.domain.PostStatus
import com.five9th.smagent.domain.PublicationStatus
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
        val post = Post(
            title = request.title?.trim()?.takeIf { it.isNotEmpty() },
            text = request.text.trim(),
            overrides = request.orOverrides(),
        )
        return toDto(posts.save(post), includePublications = false)
    }

    fun update(id: Long, request: UpdatePostRequest): PostDto {
        val post = get(id)
        if (post.status != PostStatus.DRAFT) {
            throw IllegalArgumentException("Only DRAFT posts can be edited (post $id is ${post.status})")
        }
        request.title?.let { post.title = it.trim().takeIf { t -> t.isNotEmpty() } }
        request.text?.let { post.text = it.trim() }
        request.overrides?.let { post.overrides = it.toMutableMap() }
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
        title = post.title,
        text = post.text,
        status = post.status,
        overrides = post.overrides.toMap(),
        createdAt = post.createdAt,
        updatedAt = post.updatedAt,
        publications = if (includePublications && post.id != null) {
            val postId = post.id!!
            publications.findByPostIdOrderByCreatedAtDesc(postId).map { PublicationMapper.toDto(it) }
        } else {
            emptyList()
        },
    )

    private fun CreatePostRequest.orOverrides(): MutableMap<Platform, String> =
        overrides?.toMutableMap() ?: mutableMapOf()
}
