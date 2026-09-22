package com.five9th.smagent.repo

import com.five9th.smagent.domain.AppUser
import com.five9th.smagent.domain.Post
import com.five9th.smagent.domain.PostStatus
import com.five9th.smagent.domain.Publication
import com.five9th.smagent.domain.SocialAccount
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByUsername(username: String): AppUser?
    fun countByUsername(username: String): Long
}

interface SocialAccountRepository : JpaRepository<SocialAccount, Long> {
    fun findAllByOrderByCreatedAt(pageable: Pageable): Page<SocialAccount>
}

interface PostRepository : JpaRepository<Post, Long> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<Post>
    fun findByStatusOrderByCreatedAtDesc(status: PostStatus, pageable: Pageable): Page<Post>
}

interface PublicationRepository : JpaRepository<Publication, Long> {
    fun findByPostIdOrderByCreatedAtDesc(postId: Long): List<Publication>
    fun findByPostId(postId: Long): List<Publication>
}
