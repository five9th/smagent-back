package com.five9th.smagent.post

import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.CredentialFieldKind
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.platform.PublishCommand
import com.five9th.smagent.platform.PublishResult
import com.five9th.smagent.platform.adapter.facebook.FacebookPageAccessTokenAdapter
import com.five9th.smagent.platform.adapter.reddit.RedditPasswordGrantAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublishIntegrationTest(@Autowired val mvc: MockMvc) {

    @MockitoBean
    lateinit var facebookPageTokenAdapter: FacebookPageAccessTokenAdapter

    @MockitoBean
    lateinit var redditPasswordAdapter: RedditPasswordGrantAdapter

    @BeforeEach
    fun stubAdapters() {
        whenever(facebookPageTokenAdapter.platform).thenReturn(Platform.FACEBOOK)
        whenever(facebookPageTokenAdapter.authMethod).thenReturn(AuthMethod.PAGE_ACCESS_TOKEN)
        whenever(facebookPageTokenAdapter.credentialFields).thenReturn(
            mapOf("pageId" to CredentialFieldKind.ID, "pageAccessToken" to CredentialFieldKind.TOKEN)
        )
        whenever(facebookPageTokenAdapter.canPublish).thenReturn(true)
        whenever(redditPasswordAdapter.platform).thenReturn(Platform.REDDIT)
        whenever(redditPasswordAdapter.authMethod).thenReturn(AuthMethod.PASSWORD_GRANT)
        whenever(redditPasswordAdapter.credentialFields).thenReturn(
            mapOf(
                "clientId" to CredentialFieldKind.ID,
                "clientSecret" to CredentialFieldKind.TOKEN,
                "username" to CredentialFieldKind.ID,
                "password" to CredentialFieldKind.PASSWORD,
                "subreddit" to CredentialFieldKind.ID,
            )
        )
        whenever(redditPasswordAdapter.canPublish).thenReturn(true)
    }

    private fun createAccount(platform: String, authMethod: String, extra: String): Long {
        val result = mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"$platform","authMethod":"$authMethod","credentials":{$extra}}"""
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    private fun createPost(title: String?, text: String, overrides: String? = null): Long {
        val contentJson = if (title != null) {
            """{"title":"$title","text":"$text"}"""
        } else {
            """{"text":"$text"}"""
        }
        val body = buildString {
            append("""{"content":$contentJson""")
            if (overrides != null) append(""","overrides":$overrides""")
            append("}")
        }
        val result = mvc.post("/api/posts") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `publish records success and failure per target`() {
        val fbAccount = createAccount("FACEBOOK", "PAGE_ACCESS_TOKEN", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val rdAccount = createAccount(
            "REDDIT", "PASSWORD_GRANT",
            "\"clientId\":\"c\",\"clientSecret\":\"s\",\"username\":\"u\",\"password\":\"p\",\"subreddit\":\"test\""
        )
        val postId = createPost(
            title = "Hello",
            text = "generic body",
            overrides = """{"FACEBOOK":{"title":"FB title","text":"facebook specific body"}}"""
        )

        whenever(facebookPageTokenAdapter.publish(any(), any()))
            .thenReturn(PublishResult("https://facebook.com/1/posts/abc"))
        whenever(redditPasswordAdapter.publish(any(), any()))
            .thenThrow(PlatformException("Reddit rejected the post: RATELIMIT"))

        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$fbAccount,$rdAccount]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.postStatus") { value("PUBLISHED") }
            jsonPath("$.results.length()") { value(2) }
            jsonPath("$.results[0].status") { value("SUCCESS") }
            jsonPath("$.results[0].externalUrl") { value("https://facebook.com/1/posts/abc") }
            jsonPath("$.results[1].status") { value("FAILED") }
            jsonPath("$.results[1].errorMessage") { exists() }
        }

        // the platform override content (both fields, not the base content) must reach the adapter
        val commandCaptor = argumentCaptor<PublishCommand>()
        verify(facebookPageTokenAdapter).publish(any(), commandCaptor.capture())
        assertEquals("FB title", commandCaptor.firstValue.title)
        assertEquals("facebook specific body", commandCaptor.firstValue.text)

        // publication log keeps both attempts, post detail exposes them
        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
        mvc.get("/api/posts/{id}", postId).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PUBLISHED") }
            jsonPath("$.publications.length()") { value(2) }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `all targets failing keeps post as DRAFT`() {
        val fbAccount = createAccount("FACEBOOK", "PAGE_ACCESS_TOKEN", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val postId = createPost(null, "hello")

        whenever(facebookPageTokenAdapter.publish(any(), any()))
            .thenThrow(PlatformException("Facebook API error (190): invalid token"))

        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$fbAccount]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.postStatus") { value("DRAFT") }
            jsonPath("$.results[0].status") { value("FAILED") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `published post can no longer be edited but republishing is allowed`() {
        val fbAccount = createAccount("FACEBOOK", "PAGE_ACCESS_TOKEN", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val postId = createPost(null, "hello")

        whenever(facebookPageTokenAdapter.publish(any(), any()))
            .thenReturn(PublishResult("https://facebook.com/1/posts/abc"))

        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$fbAccount]}"""
        }.andExpect { status { isOk() } }

        mvc.put("/api/posts/{id}", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":{"text":"edited"}}"""
        }.andExpect { status { isBadRequest() } }

        // republish adds another publication row
        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$fbAccount]}"""
        }.andExpect { status { isOk() } }
        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            jsonPath("$.length()") { value(2) }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `deleting a published post requires the flag and keeps publications`() {
        val fbAccount = createAccount("FACEBOOK", "PAGE_ACCESS_TOKEN", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val postId = createPost(null, "hello")

        whenever(facebookPageTokenAdapter.publish(any(), any()))
            .thenReturn(PublishResult("https://facebook.com/1/posts/abc"))

        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$fbAccount]}"""
        }.andExpect { status { isOk() } }

        // PUBLISHED without the explicit flag -> 400
        mvc.delete("/api/posts/{id}", postId).andExpect { status { isBadRequest() } }

        // not implemented yet -> 501
        mvc.delete("/api/posts/{id}?deletePublications=true", postId).andExpect { status { isNotImplemented() } }

        // explicit local-only delete -> 204, history survives
        mvc.delete("/api/posts/{id}?deletePublications=false", postId).andExpect { status { isNoContent() } }
        mvc.get("/api/posts/{id}", postId).andExpect { status { isNotFound() } }
        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            jsonPath("$.length()") { value(1) }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `publish to a manual PROFILE_URL account records a failed attempt with the link`() {
        val manualAccount = createAccount(
            "X", "PROFILE_URL",
            "\"profileUrl\":\"https://x.com/my_page\""
        )
        val postId = createPost("t", "text")

        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$manualAccount]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.postStatus") { value("DRAFT") }
            jsonPath("$.results[0].status") { value("FAILED") }
            jsonPath("$.results[0].errorMessage") {
                value(org.hamcrest.Matchers.containsString("https://x.com/my_page"))
            }
        }

        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].status") { value("FAILED") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `manual publish records SUCCESS, flips the post and is repeatable`() {
        val manualAccount = createAccount(
            "X", "PROFILE_URL",
            "\"profileUrl\":\"https://x.com/my_page\""
        )
        val postId = createPost("t", "text")

        mvc.post("/api/posts/{id}/publish-manual", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountId":$manualAccount,"externalUrl":"https://x.com/my_page/status/1"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.postStatus") { value("PUBLISHED") }
            jsonPath("$.results.length()") { value(1) }
            jsonPath("$.results[0].status") { value("SUCCESS") }
            jsonPath("$.results[0].publishedManually") { value(true) }
            jsonPath("$.results[0].externalUrl") { value("https://x.com/my_page/status/1") }
        }

        // repeatable, and externalUrl is optional the second time
        mvc.post("/api/posts/{id}/publish-manual", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountId":$manualAccount}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.results[0].status") { value("SUCCESS") }
            jsonPath("$.results[0].externalUrl") { value(org.hamcrest.Matchers.nullValue()) }
        }

        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].publishedManually") { value(true) }
            jsonPath("$[0].status") { value("SUCCESS") }
        }

        // manual success counts: the post is PUBLISHED and no longer editable
        mvc.put("/api/posts/{id}", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":{"text":"nope"}}"""
        }.andExpect { status { isBadRequest() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `manual publish works for an API account without calling the adapter`() {
        val fbAccount = createAccount("FACEBOOK", "PAGE_ACCESS_TOKEN", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val postId = createPost("t", "text")

        mvc.post("/api/posts/{id}/publish-manual", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountId":$fbAccount}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.results[0].status") { value("SUCCESS") }
            jsonPath("$.results[0].platform") { value("FACEBOOK") }
        }

        verify(facebookPageTokenAdapter, never()).publish(any(), any())
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `manual publish with malformed externalUrl returns 400`() {
        val manualAccount = createAccount(
            "X", "PROFILE_URL",
            "\"profileUrl\":\"https://x.com/my_page\""
        )
        val postId = createPost("t", "text")

        mvc.post("/api/posts/{id}/publish-manual", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountId":$manualAccount,"externalUrl":"not a url"}"""
        }.andExpect { status { isBadRequest() } }

        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            jsonPath("$.length()") { value(0) }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `manual publish to unknown account returns 404`() {
        val postId = createPost("t", "text")
        mvc.post("/api/posts/{id}/publish-manual", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountId":999}"""
        }.andExpect { status { isNotFound() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `publish to unknown account returns 404`() {
        val postId = createPost("t", "text")
        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[999]}"""
        }.andExpect { status { isNotFound() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `one unknown account id aborts the round without publishing the valid ones`() {
        val fbAccount = createAccount("FACEBOOK", "PAGE_ACCESS_TOKEN", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val postId = createPost("t", "text")

        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$fbAccount,999]}"""
        }.andExpect { status { isNotFound() } }

        // the valid account must not have been touched either
        verify(facebookPageTokenAdapter, never()).publish(any(), any())
        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            jsonPath("$.length()") { value(0) }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `editing a draft replaces content and overrides`() {
        val postId = createPost("t", "body")

        mvc.put("/api/posts/{id}", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"content":{"title":null,"text":"new body"},
                 "overrides":{"REDDIT":{"title":"rd title","text":"rd body"}}}
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.title") { value(org.hamcrest.Matchers.nullValue()) }
            jsonPath("$.content.text") { value("new body") }
            jsonPath("$.overrides.REDDIT.title") { value("rd title") }
            jsonPath("$.overrides.REDDIT.text") { value("rd body") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `post requires text`() {
        mvc.post("/api/posts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":{"title":"only title"}}"""
        }.andExpect { status { isBadRequest() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `override content requires text`() {
        mvc.post("/api/posts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":{"text":"body"},"overrides":{"FACEBOOK":{"text":"  "}}}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `posts require authentication`() {
        mvc.get("/api/posts").andExpect { status { isUnauthorized() } }
    }
}
