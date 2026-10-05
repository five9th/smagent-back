package com.five9th.smagent

import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.platform.PublishResult
import com.five9th.smagent.platform.adapter.facebook.FacebookPageAccessTokenAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// rollback after every method: tests in one class share the DB (context-level isolation only)
@Transactional
class SoftDeleteIntegrationTest(@Autowired val mvc: MockMvc) {

    @MockitoBean
    lateinit var facebookPageTokenAdapter: FacebookPageAccessTokenAdapter

    @BeforeEach
    fun stubAdapter() {
        whenever(facebookPageTokenAdapter.platform).thenReturn(Platform.FACEBOOK)
        whenever(facebookPageTokenAdapter.authMethod).thenReturn(AuthMethod.PAGE_ACCESS_TOKEN)
        whenever(facebookPageTokenAdapter.requiredCredentialFields).thenReturn(setOf("pageId", "pageAccessToken"))
        whenever(facebookPageTokenAdapter.publish(any(), any()))
            .thenReturn(PublishResult("https://facebook.com/1/posts/abc"))
    }

    private fun createAccount(name: String): Long {
        val result = mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"FACEBOOK","authMethod":"PAGE_ACCESS_TOKEN","displayName":"$name","credentials":{"pageId":"1","pageAccessToken":"tok"}}"""
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    private fun createPost(text: String): Long {
        val result = mvc.post("/api/posts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":{"text":"$text"}}"""
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    private fun publish(postId: Long, accountId: Long) {
        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$accountId]}"""
        }.andExpect { status { isOk() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `soft-deleted account disappears but its publication history keeps the snapshot`() {
        val accountId = createAccount("History Page")
        val postId = createPost("hello")
        publish(postId, accountId)

        mvc.delete("/api/social-accounts/{id}", accountId).andExpect { status { isNoContent() } }

        // hidden everywhere
        mvc.get("/api/social-accounts").andExpect {
            jsonPath("$.length()") { value(0) }
        }
        mvc.get("/api/social-accounts/{id}", accountId).andExpect { status { isNotFound() } }
        mvc.post("/api/social-accounts/{id}/verify", accountId).andExpect { status { isNotFound() } }
        mvc.put("/api/social-accounts/{id}", accountId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"nope"}"""
        }.andExpect { status { isNotFound() } }

        // publish to a deleted account -> 404
        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$accountId]}"""
        }.andExpect { status { isNotFound() } }

        // history survives with the publish-time snapshot
        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].platform") { value("FACEBOOK") }
            jsonPath("$[0].accountName") { value("History Page") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `showDeleted reveals soft-deleted account with empty credentials`() {
        val accountId = createAccount("Gone Page")
        mvc.delete("/api/social-accounts/{id}", accountId).andExpect { status { isNoContent() } }

        mvc.get("/api/social-accounts?showDeleted=true").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(accountId.toInt()) }
            jsonPath("$[0].deletedAt") { exists() }
            jsonPath("$[0].credentialsMasked.length()") { value(0) }
        }

        // deleting twice -> second time it's gone (404)
        mvc.delete("/api/social-accounts/{id}", accountId).andExpect { status { isNotFound() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `draft post soft-deletes without the flag and publications remain queryable`() {
        val postId = createPost("draft only")
        mvc.delete("/api/posts/{id}", postId).andExpect { status { isNoContent() } }

        mvc.get("/api/posts").andExpect { jsonPath("$.length()") { value(0) } }
        mvc.get("/api/posts/{id}", postId).andExpect { status { isNotFound() } }

        // editing a deleted post -> 404
        mvc.put("/api/posts/{id}", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":{"text":"nope"}}"""
        }.andExpect { status { isNotFound() } }

        mvc.get("/api/posts?showDeleted=true").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].deletedAt") { exists() }
        }

        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            jsonPath("$.length()") { value(0) }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `showDeleted combines with the status filter`() {
        val draftPost = createPost("still here")
        createPost("to delete") // will be deleted below
        val toDelete = createPost("also deleting")
        publish(toDelete, createAccount("P"))

        mvc.delete("/api/posts/{id}", toDelete).andExpect { status { isBadRequest() } } // PUBLISHED, no flag
        mvc.delete("/api/posts/{id}?deletePublications=false", toDelete).andExpect { status { isNoContent() } }

        mvc.get("/api/posts?status=DRAFT").andExpect {
            jsonPath("$.length()") { value(2) } // draftPost + "to delete"
        }
        mvc.get("/api/posts?status=PUBLISHED").andExpect {
            jsonPath("$.length()") { value(0) } // the only published one is deleted
        }
        mvc.get("/api/posts?status=PUBLISHED&showDeleted=true").andExpect {
            jsonPath("$.length()") { value(1) }
        }
        mvc.get("/api/posts?showDeleted=true").andExpect {
            jsonPath("$.length()") { value(3) }
        }
        // sanity: draftPost untouched
        mvc.get("/api/posts/{id}", draftPost).andExpect { status { isOk() } }
    }
}
