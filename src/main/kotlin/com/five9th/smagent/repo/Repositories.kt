package com.five9th.smagent.repo

import com.five9th.smagent.domain.AppUser
import com.five9th.smagent.domain.Post
import com.five9th.smagent.domain.PostStatus
import com.five9th.smagent.domain.Publication
import com.five9th.smagent.domain.SocialAccount
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByUsername(username: String): AppUser?
}

interface SocialAccountRepository : JpaRepository<SocialAccount, Long>

interface PostRepository : JpaRepository<Post, Long> {
    fun findByStatusOrderByCreatedAtDesc(status: PostStatus): List<Post>
    fun findAllByOrderByCreatedAtDesc(): List<Post>
}

interface PublicationRepository : JpaRepository<Publication, Long> {
    // join fetch: publications are mapped to DTOs outside the repository transaction
    @Query("select p from Publication p join fetch p.socialAccount where p.post.id = :postId order by p.createdAt desc")
    fun findByPostIdOrderByCreatedAtDesc(@Param("postId") postId: Long): List<Publication>

    @Query("select p from Publication p join fetch p.socialAccount where p.post.id = :postId")
    fun findByPostId(@Param("postId") postId: Long): List<Publication>
}
