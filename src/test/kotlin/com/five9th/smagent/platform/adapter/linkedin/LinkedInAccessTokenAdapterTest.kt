package com.five9th.smagent.platform.adapter.linkedin

import com.five9th.smagent.config.AppProperties
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.platform.PublishCommand
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/** /rest/posts against a MockRestServiceServer: headers, body shape, error handling. */
class LinkedInAccessTokenAdapterTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val adapter = LinkedInAccessTokenAdapter(AppProperties(), builder)

    private val creds = """{"accessToken":"tok"}"""

    @Test
    fun `verify resolves the member name from userinfo`() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andRespond(withSuccess("""{"sub":"abc123","name":"Jane Doe"}""", MediaType.APPLICATION_JSON))

        assertEquals("Jane Doe", adapter.verify(creds).displayName)
    }

    @Test
    fun `verify falls back to the member id when the profile scope is missing`() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
            .andRespond(withSuccess("""{"sub":"abc123"}""", MediaType.APPLICATION_JSON))

        assertEquals("abc123", adapter.verify(creds).displayName)
    }

    @Test
    fun `verify survives a live token without the openid scope`() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN))

        assertNull(adapter.verify(creds).displayName)
    }

    @Test
    fun `publish posts commentary, link and version headers, and reads the post urn`() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
            .andRespond(withSuccess("""{"sub":"abc123"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andExpect(header("X-Restli-Protocol-Version", "2.0.0"))
            .andExpect(header("LinkedIn-Version", "202609"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.author").value("urn:li:person:abc123"))
            .andExpect(jsonPath("$.commentary").value("hello\n\nhttps://example.com/a"))
            .andExpect(jsonPath("$.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.lifecycleState").value("PUBLISHED"))
            .andRespond(withStatus(HttpStatus.CREATED).header("x-restli-id", "urn:li:share:6844785523593134080"))

        val result = adapter.publish(creds, PublishCommand(title = "ignored", text = "hello", link = "https://example.com/a"))

        assertEquals("https://www.linkedin.com/feed/update/urn:li:share:6844785523593134080", result.externalUrl)
    }

    @Test
    fun `publish falls back to the accessTokenSubject placeholder without the openid scope`() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN))
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
            .andExpect(jsonPath("$.author").value("urn:li:person:{accessTokenSubject}"))
            .andRespond(withStatus(HttpStatus.CREATED).header("x-restli-id", "urn:li:share:1"))

        adapter.publish(creds, PublishCommand(title = null, text = "hello"))
    }

    @Test
    fun `publish surfaces LinkedIn error messages`() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
            .andRespond(withSuccess("""{"sub":"abc123"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
            .andRespond(
                withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"status":429,"code":"TOO_MANY_REQUESTS","message":"Throttle limit for requests is reached."}""")
            )

        val e = assertFailsWith<PlatformException> { adapter.publish(creds, PublishCommand(null, "hello")) }
        assertEquals("LinkedIn API error (429): Throttle limit for requests is reached.", e.message)
    }

    @Test
    fun `publish without the post id header fails`() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
            .andRespond(withSuccess("""{"sub":"abc123"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
            .andRespond(withStatus(HttpStatus.CREATED))

        assertFailsWith<PlatformException> { adapter.publish(creds, PublishCommand(null, "hello")) }
    }

    @Test
    fun `publish with an expired token fails on userinfo with the 60-day hint`() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val e = assertFailsWith<PlatformException> { adapter.publish(creds, PublishCommand(null, "hello")) }
        assertEquals(
            "LinkedIn API error (401): Unauthorized (invalid or expired access token — LinkedIn tokens last ~60 days)",
            e.message,
        )
    }

    @Test
    fun `network failures become PlatformException`() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
            .andRespond(withServerError())

        assertFailsWith<PlatformException> { adapter.verify(creds) }
    }
}
