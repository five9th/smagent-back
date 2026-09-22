package com.five9th.smagent.post

import com.five9th.smagent.domain.Platform
import com.five9th.smagent.platform.FacebookAdapter
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.platform.PublishCommand
import com.five9th.smagent.platform.PublishResult
import com.five9th.smagent.platform.RedditAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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
    lateinit var facebookAdapter: FacebookAdapter

    @MockitoBean
    lateinit var redditAdapter: RedditAdapter

    @BeforeEach
    fun stubAdapters() {
        whenever(facebookAdapter.platform).thenReturn(Platform.FACEBOOK)
        whenever(facebookAdapter.requiredCredentialFields).thenReturn(setOf("pageId", "pageAccessToken"))
        whenever(redditAdapter.platform).thenReturn(Platform.REDDIT)
        whenever(redditAdapter.requiredCredentialFields)
            .thenReturn(setOf("clientId", "clientSecret", "username", "password", "subreddit"))
    }

    private fun createAccount(platform: String, extra: String): Long {
        val result = mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"$platform","credentials":{$extra}}"""
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    private fun createPost(title: String?, text: String, overrides: String? = null): Long {
        val body = buildString {
            append("{")
            if (title != null) append("\"title\":\"$title\",")
            append("\"text\":\"$text\"")
            if (overrides != null) append(",\"overrides\":$overrides")
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
        val fbAccount = createAccount("FACEBOOK", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val rdAccount = createAccount(
            "REDDIT",
            "\"clientId\":\"c\",\"clientSecret\":\"s\",\"username\":\"u\",\"password\":\"p\",\"subreddit\":\"test\""
        )
        val postId = createPost(
            title = "Hello",
            text = "generic body",
            overrides = """{"FACEBOOK":"facebook specific body"}"""
        )

        whenever(facebookAdapter.publish(any(), any()))
            .thenReturn(PublishResult("https://facebook.com/1/posts/abc"))
        whenever(redditAdapter.publish(any(), any()))
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

        // the platform override text (not the base text) must reach the adapter
        val commandCaptor = argumentCaptor<PublishCommand>()
        verify(facebookAdapter).publish(any(), commandCaptor.capture())
        assertEquals("facebook specific body", commandCaptor.firstValue.text)
        assertEquals("Hello", commandCaptor.firstValue.title)

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
        val fbAccount = createAccount("FACEBOOK", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val postId = createPost(null, "hello")

        whenever(facebookAdapter.publish(any(), any()))
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
        val fbAccount = createAccount("FACEBOOK", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val postId = createPost(null, "hello")

        whenever(facebookAdapter.publish(any(), any()))
            .thenReturn(PublishResult("https://facebook.com/1/posts/abc"))

        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$fbAccount]}"""
        }.andExpect { status { isOk() } }

        mvc.put("/api/posts/{id}", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"text":"edited"}"""
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
    fun `deleting a post removes its publications`() {
        val fbAccount = createAccount("FACEBOOK", "\"pageId\":\"1\",\"pageAccessToken\":\"tok\"")
        val postId = createPost(null, "hello")

        whenever(facebookAdapter.publish(any(), any()))
            .thenReturn(PublishResult("https://facebook.com/1/posts/abc"))

        mvc.post("/api/posts/{id}/publish", postId) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountIds":[$fbAccount]}"""
        }.andExpect { status { isOk() } }

        mvc.delete("/api/posts/{id}", postId).andExpect { status { isNoContent() } }
        mvc.get("/api/posts/{id}", postId).andExpect { status { isNotFound() } }
        mvc.get("/api/publications") { param("postId", postId.toString()) }.andExpect {
            jsonPath("$.length()") { value(0) }
        }
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
    fun `post requires text`() {
        mvc.post("/api/posts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"only title"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `posts require authentication`() {
        mvc.get("/api/posts").andExpect { status { isUnauthorized() } }
    }
}
