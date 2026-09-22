package com.five9th.smagent.repo

import com.five9th.smagent.domain.AppUser
import com.five9th.smagent.domain.Post
import com.five9th.smagent.domain.PostStatus
import com.five9th.smagent.domain.Publication
import com.five9th.smagent.domain.SocialAccount
import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByUsername(username: String): AppUser?
}

interface SocialAccountRepository : JpaRepository<SocialAccount, Long>

interface PostRepository : JpaRepository<Post, Long> {
    fun findByStatusOrderByCreatedAtDesc(status: PostStatus): List<Post>
    fun findAllByOrderByCreatedAtDesc(): List<Post>
}

interface PublicationRepository : JpaRepository<Publication, Long> {
    fun findByPostIdOrderByCreatedAtDesc(postId: Long): List<Publication>
    fun findByPostId(postId: Long): List<Publication>
}
