package com.five9th.smagent.repo

import com.five9th.smagent.domain.AiProvider
import com.five9th.smagent.domain.AppUser
import com.five9th.smagent.domain.FeedbackItem
import com.five9th.smagent.domain.GenerationSettings
import com.five9th.smagent.domain.Post
import com.five9th.smagent.domain.PostStatus
import com.five9th.smagent.domain.Publication
import com.five9th.smagent.domain.SocialAccount
import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByUsername(username: String): AppUser?
}

interface SocialAccountRepository : JpaRepository<SocialAccount, Long> {
    fun findAllByDeletedAtIsNullOrderByCreatedAtDesc(): List<SocialAccount>
    fun findAllByOrderByCreatedAtDesc(): List<SocialAccount>
    fun findByIdAndDeletedAtIsNull(id: Long): SocialAccount?
    fun findAllByIdInAndDeletedAtIsNull(ids: List<Long>): List<SocialAccount>
}

interface PostRepository : JpaRepository<Post, Long> {
    fun findByStatusOrderByCreatedAtDesc(status: PostStatus): List<Post>
    fun findAllByOrderByCreatedAtDesc(): List<Post>
    fun findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(status: PostStatus): List<Post>
    fun findAllByDeletedAtIsNullOrderByCreatedAtDesc(): List<Post>
    fun findByIdAndDeletedAtIsNull(id: Long): Post?
}

interface PublicationRepository : JpaRepository<Publication, Long> {
    fun findByPostIdOrderByCreatedAtDesc(postId: Long): List<Publication>
    fun findByPostId(postId: Long): List<Publication>
}

interface FeedbackItemRepository : JpaRepository<FeedbackItem, Long> {
    fun findAllByOrderByCreatedAtDesc(): List<FeedbackItem>
}

interface AiProviderRepository : JpaRepository<AiProvider, Long> {
    fun findAllByOrderByCreatedAtDesc(): List<AiProvider>
}

interface GenerationSettingsRepository : JpaRepository<GenerationSettings, Long> {
    fun findFirstByOrderByIdAsc(): GenerationSettings?
}
