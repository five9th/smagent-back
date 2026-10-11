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
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/** Company page posting against a MockRestServiceServer: org lookup, author URN, errors. */
class LinkedInOrganizationAccessTokenAdapterTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val adapter = LinkedInOrganizationAccessTokenAdapter(AppProperties(), builder)

    private val creds = """{"organizationId":"5515715","accessToken":"tok"}"""

    @Test
    fun `verify resolves the page name from the organization lookup`() {
        server.expect(requestTo("https://api.linkedin.com/rest/organizations/5515715"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andExpect(header("X-Restli-Protocol-Version", "2.0.0"))
            .andExpect(header("LinkedIn-Version", "202609"))
            .andRespond(
                withSuccess(
                    """{"id":5515715,"localizedName":"Acme Corp","vanityName":"acme"}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        assertEquals("Acme Corp", adapter.verify(creds).displayName)
    }

    @Test
    fun `verify normalizes a pasted organization URN`() {
        server.expect(requestTo("https://api.linkedin.com/rest/organizations/5515715"))
            .andRespond(withSuccess("""{"localizedName":"Acme"}""", MediaType.APPLICATION_JSON))

        assertEquals(
            "Acme",
            adapter.verify("""{"organizationId":"urn:li:organization:5515715","accessToken":"tok"}""").displayName,
        )
    }

    @Test
    fun `verify survives a live token without lookup access`() {
        server.expect(requestTo("https://api.linkedin.com/rest/organizations/5515715"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN))

        assertNull(adapter.verify(creds).displayName)
    }

    @Test
    fun `verify surfaces a wrong organization id`() {
        server.expect(requestTo("https://api.linkedin.com/rest/organizations/5515715"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"status":404,"code":"NOT_FOUND","message":"Organization 5515715 is inactive"}""")
            )

        val e = assertFailsWith<PlatformException> { adapter.verify(creds) }
        assertEquals("LinkedIn API error (404): Organization 5515715 is inactive", e.message)
    }

    @Test
    fun `publish posts as the organization without any lookup`() {
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andExpect(header("X-Restli-Protocol-Version", "2.0.0"))
            .andExpect(header("LinkedIn-Version", "202609"))
            .andExpect(jsonPath("$.author").value("urn:li:organization:5515715"))
            .andExpect(jsonPath("$.commentary").value("hello\n\nhttps://example.com/a"))
            .andExpect(jsonPath("$.lifecycleState").value("PUBLISHED"))
            .andRespond(withStatus(HttpStatus.CREATED).header("x-restli-id", "urn:li:share:6844785523593134080"))

        val result = adapter.publish(creds, PublishCommand(title = "ignored", text = "hello", link = "https://example.com/a"))

        assertEquals("https://www.linkedin.com/feed/update/urn:li:share:6844785523593134080", result.externalUrl)
    }

    @Test
    fun `publish authorizes with the URN from the credentials`() {
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
            .andExpect(jsonPath("$.author").value("urn:li:organization:5515715"))
            .andRespond(withStatus(HttpStatus.CREATED).header("x-restli-id", "urn:li:share:1"))

        adapter.publish(
            """{"organizationId":"urn:li:organization:5515715","accessToken":"tok"}""",
            PublishCommand(null, "hello"),
        )
    }

    @Test
    fun `publish surfaces a missing page role`() {
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
            .andRespond(
                withStatus(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"status":403,"code":"ACCESS_DENIED","message":"Insufficient permissions to perform the requested action."}""")
            )

        val e = assertFailsWith<PlatformException> { adapter.publish(creds, PublishCommand(null, "hello")) }
        assertEquals("LinkedIn API error (403): Insufficient permissions to perform the requested action.", e.message)
    }
}
