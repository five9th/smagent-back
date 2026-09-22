package com.five9th.smagent.post

import com.five9th.smagent.common.NotFoundException
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.domain.Post
import com.five9th.smagent.domain.Publication
import com.five9th.smagent.domain.PublicationStatus
import com.five9th.smagent.domain.SocialAccount
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.platform.PublishCommand
import com.five9th.smagent.platform.SocialPlatformAdapter
import com.five9th.smagent.repo.PublicationRepository
import com.five9th.smagent.repo.SocialAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Publishes a post to the selected accounts. Mirrors the "record before external
 * call" rule: every attempt gets a Publication row (PENDING) before the platform
 * API is touched, then is updated to SUCCESS or FAILED. One failing target never
 * blocks the others.
 */
@Service
class PublishService(
    private val publications: PublicationRepository,
    private val postService: PostService,
    private val accounts: SocialAccountRepository,
    private val crypto: CryptoService,
    private val adapters: List<SocialPlatformAdapter>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(postId: Long, accountIds: List<Long>): PublishResponse {
        val post = postService.get(postId)
        val results = accountIds.map { accountId -> publishToAccount(post, accountId) }
        postService.markPublishedIfAnySuccess(post)
        return PublishResponse(
            postId = post.id,
            postStatus = postService.get(postId).status,
            results = results,
        )
    }

    private fun publishToAccount(post: Post, accountId: Long): PublishOutcome {
        val account = accounts.findById(accountId)
            .orElseThrow { NotFoundException("Social account $accountId not found") }

        val adapter = adapters.firstOrNull { it.platform == account.platform }

        // Record the attempt before the external call
        val publication = publications.save(
            Publication(post = post, socialAccount = account, status = PublicationStatus.PENDING)
        )

        if (adapter == null) {
            return fail(publication, account, "No adapter for platform ${account.platform}")
        }

        val command = PublishCommand(
            title = post.title,
            text = post.overrides[account.platform] ?: post.text,
        )

        return try {
            val result = adapter.publish(crypto.decrypt(account.credentialsEnc), command)
            publication.status = PublicationStatus.SUCCESS
            publication.externalUrl = result.externalUrl
            publication.publishedAt = Instant.now()
            publications.save(publication)
            PublishOutcome(
                accountId = account.id,
                platform = account.platform,
                accountName = account.displayName,
                status = PublicationStatus.SUCCESS,
                externalUrl = result.externalUrl,
                errorMessage = null,
            )
        } catch (e: PlatformException) {
            log.warn("Publish to {} (account {}) failed: {}", account.platform, account.id, e.message)
            fail(publication, account, e.message ?: "Publish failed")
        }
    }

    private fun fail(publication: Publication, account: SocialAccount, message: String): PublishOutcome {
        publication.status = PublicationStatus.FAILED
        publication.errorMessage = message.take(2000)
        publications.save(publication)
        return PublishOutcome(
            accountId = account.id,
            platform = account.platform,
            accountName = account.displayName,
            status = PublicationStatus.FAILED,
            externalUrl = null,
            errorMessage = message,
        )
    }
}

object PublicationMapper {
    fun toDto(p: Publication): PublicationDto = PublicationDto(
        id = p.id,
        postId = p.post.id,
        accountId = p.socialAccount.id,
        platform = p.socialAccount.platform,
        accountName = p.socialAccount.displayName,
        status = p.status,
        externalUrl = p.externalUrl,
        errorMessage = p.errorMessage,
        publishedAt = p.publishedAt,
        createdAt = p.createdAt,
    )
}
