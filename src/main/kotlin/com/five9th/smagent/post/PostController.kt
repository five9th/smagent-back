package com.five9th.smagent.post

import com.five9th.smagent.domain.PostStatus
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/posts")
class PostController(
    private val postService: PostService,
    private val publishService: PublishService,
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: PostStatus?,
        @RequestParam(defaultValue = "false") showDeleted: Boolean,
    ): List<PostDto> = postService.list(status, showDeleted)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreatePostRequest): PostDto = postService.create(request)

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): PostDto = postService.getDto(id)

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdatePostRequest): PostDto =
        postService.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: Long,
        @RequestParam(required = false) deletePublications: Boolean?,
    ) = postService.delete(id, deletePublications)

    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: Long, @Valid @RequestBody request: PublishRequest): PublishResponse =
        publishService.publish(id, request.accountIds)
}

@RestController
@RequestMapping("/api/publications")
class PublicationsController(private val postService: PostService) {

    @GetMapping
    fun list(@RequestParam postId: Long): List<PublicationDto> =
        postService.publicationsOf(postId)
}
