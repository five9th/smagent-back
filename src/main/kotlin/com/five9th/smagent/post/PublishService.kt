package com.five9th.smagent.post

import com.five9th.smagent.common.NotFoundException
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.domain.Post
import com.five9th.smagent.domain.Publication
import com.five9th.smagent.domain.PublicationStatus
import com.five9th.smagent.domain.SocialAccount
import com.five9th.smagent.platform.AdapterRegistry
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.platform.PublishCommand
import com.five9th.smagent.repo.PublicationRepository
import com.five9th.smagent.repo.SocialAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Instant

/**
 * Publishes a post to the selected accounts. Mirrors the "record before external
 * call" rule: every attempt gets a Publication row (PENDING) before the platform
 * API is touched, then is updated to SUCCESS or FAILED. One failing target never
 * blocks the others. Account IDs are all resolved up front, so an unknown or
 * soft-deleted ID fails the request before anything is published.
 */
@Service
class PublishService(
    private val publications: PublicationRepository,
    private val postService: PostService,
    private val accounts: SocialAccountRepository,
    private val crypto: CryptoService,
    private val registry: AdapterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(postId: Long, accountIds: List<Long>): PublishResponse {
        val post = postService.get(postId)
        // Resolve every account before any Publication row is created: one bad ID
        // then fails the whole request (404) instead of aborting a half-executed
        // round whose status refresh would never run.
        val accountsById = accounts.findAllByIdInAndDeletedAtIsNull(accountIds).associateBy { it.id }
        val missing = accountIds.filter { it !in accountsById }
        if (missing.isNotEmpty()) {
            throw NotFoundException("Social accounts not found: ${missing.joinToString(", ")}")
        }
        val results = accountIds.map { publishToAccount(post, accountsById.getValue(it)) }
        postService.markPublishedIfAnySuccess(post)
        return PublishResponse(
            postId = post.id,
            postStatus = postService.get(postId).status,
            results = results,
        )
    }

    private fun publishToAccount(post: Post, account: SocialAccount): PublishOutcome {
        val adapter = registry.find(account.platform, account.authMethod)

        // Record the attempt before the external call; snapshots survive later
        // account rename or soft-delete
        val publication = publications.save(
            Publication(
                post = post,
                socialAccount = account,
                platform = account.platform,
                accountName = account.displayName,
                status = PublicationStatus.PENDING,
            )
        )

        if (adapter == null) {
            return fail(publication, account, "No adapter for ${account.platform}/${account.authMethod}")
        }

        // The per-platform override wins field-by-field; missing fields fall back
        // to the post's own content (so e.g. a text-only override keeps the title).
        val override = post.overrides[account.platform]
        val command = PublishCommand(
            title = override?.title ?: post.content.title,
            text = override?.text ?: post.content.text,
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
                publishedManually = publication.publishedManually,
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
            publishedManually = publication.publishedManually,
            externalUrl = null,
            errorMessage = message,
        )
    }

    /**
     * Records a publication made by hand: no adapter call, just a SUCCESS row
     * (publishedManually) that counts toward the post's PUBLISHED flip.
     */
    fun publishManually(postId: Long, request: ManualPublishRequest): PublishResponse {
        val post = postService.get(postId)
        val account = accounts.findByIdAndDeletedAtIsNull(request.accountId)
            ?: throw NotFoundException("Social account ${request.accountId} not found")
        val externalUrl = request.externalUrl?.let { requireHttpUrl(it) }

        val publication = publications.save(
            Publication(
                post = post,
                socialAccount = account,
                platform = account.platform,
                accountName = account.displayName,
                status = PublicationStatus.SUCCESS,
                externalUrl = externalUrl,
                publishedAt = Instant.now(),
                publishedManually = true,
            )
        )
        postService.markPublishedIfAnySuccess(post)
        return PublishResponse(
            postId = post.id,
            postStatus = postService.get(postId).status,
            results = listOf(
                PublishOutcome(
                    accountId = account.id,
                    platform = account.platform,
                    accountName = account.displayName,
                    status = publication.status,
                    publishedManually = publication.publishedManually,
                    externalUrl = publication.externalUrl,
                    errorMessage = null,
                )
            ),
        )
    }

    private fun requireHttpUrl(url: String): String {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("externalUrl is not a valid URL: '$url'")
        }
        if (uri.scheme !in HTTP_SCHEMES || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("externalUrl must be an http(s) URL: '$url'")
        }
        return url
    }

    companion object {
        private val HTTP_SCHEMES = setOf("http", "https")
    }
}

object PublicationMapper {
    fun toDto(p: Publication): PublicationDto = PublicationDto(
        id = p.id,
        postId = p.post.id,
        accountId = p.socialAccount.id,
        platform = p.platform,
        accountName = p.accountName,
        status = p.status,
        publishedManually = p.publishedManually,
        externalUrl = p.externalUrl,
        errorMessage = p.errorMessage,
        publishedAt = p.publishedAt,
        createdAt = p.createdAt,
    )
}
