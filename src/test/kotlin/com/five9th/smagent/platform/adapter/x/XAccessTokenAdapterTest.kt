package com.five9th.smagent.platform.adapter.x

import com.five9th.smagent.config.AppProperties
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.settings.SettingsService
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import com.five9th.smagent.platform.PublishCommand
import java.util.Base64
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
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

/** /2/tweets + the refresh-on-401 rotation against a MockRestServiceServer. */
class XAccessTokenAdapterTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val settings = mock<SettingsService>()
    private val adapter = XAccessTokenAdapter(
        AppProperties(),
        builder,
        settings,
    )

    private val creds = """{"accessToken":"tok","refreshToken":"rtok"}"""

    init {
        whenever(settings.x()).thenReturn(SettingsService.ClientCredentials("cid", "csec"))
    }

    private fun basicAuth() =
        "Basic " + Base64.getEncoder().encodeToString("cid:csec".toByteArray())

    /** The form the refresh grant must send (Basic auth carries the app credentials). */
    private fun expectedRefreshForm(refreshToken: String): LinkedMultiValueMap<String, String> =
        LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "refresh_token")
            add("refresh_token", refreshToken)
        }

    private fun expectRefresh(refreshToken: String) {
        server.expect(requestTo("https://api.x.com/2/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, basicAuth()))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formData(expectedRefreshForm(refreshToken)))
            .andRespond(
                withSuccess(
                    """{"token_type":"bearer","expires_in":7200,"access_token":"tok2","scope":"tweet.read tweet.write users.read offline.access","refresh_token":"rtok2"}""",
                    MediaType.APPLICATION_JSON,
                )
            )
    }

    @Test
    fun `verify resolves the name and handle from users me`() {
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andRespond(
                withSuccess("""{"data":{"id":"2244994945","name":"Jane Doe","username":"janedoe"}}""", MediaType.APPLICATION_JSON)
            )

        val result = adapter.verify(creds)

        assertEquals("Jane Doe (@janedoe)", result.displayName)
        assertNull(result.refreshedCredentials)
    }

    @Test
    fun `verify falls back to the handle when the profile has no name`() {
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(withSuccess("""{"data":{"id":"1","username":"jdoe"}}""", MediaType.APPLICATION_JSON))

        assertEquals("jdoe (@jdoe)", adapter.verify(creds).displayName)
    }

    @Test
    fun `verify refreshes an expired token and reports the rotated pair`() {
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        expectRefresh("rtok")
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok2"))
            .andRespond(
                withSuccess("""{"data":{"id":"1","name":"Jane Doe","username":"janedoe"}}""", MediaType.APPLICATION_JSON)
            )

        val result = adapter.verify(creds)

        assertEquals("Jane Doe (@janedoe)", result.displayName)
        assertEquals(mapOf("accessToken" to "tok2", "refreshToken" to "rtok2"), result.refreshedCredentials)
    }

    @Test
    fun `verify without a stored refresh token fails with the re-run hint`() {
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val e = assertFailsWith<PlatformException> {
            adapter.verify("""{"accessToken":"tok","refreshToken":""}""")
        }
        assertEquals(
            "X access token rejected (401) and no refresh token is stored — re-run the OAuth flow",
            e.message,
        )
    }

    @Test
    fun `a failed refresh surfaces the re-run hint`() {
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        server.expect(requestTo("https://api.x.com/2/oauth2/token"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"invalid_grant","error_description":"refresh token expired"}""")
            )

        val e = assertFailsWith<PlatformException> { adapter.verify(creds) }
        assertEquals(
            "X token refresh failed (400): refresh token expired — re-run the OAuth flow to reconnect the account",
            e.message,
        )
    }

    @Test
    fun `a second 401 after a refresh fails with the re-run hint`() {
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        expectRefresh("rtok")
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val e = assertFailsWith<PlatformException> { adapter.verify(creds) }
        assertEquals(
            "X API error (401): Unauthorized (access token rejected even after a refresh — re-run the OAuth flow)",
            e.message,
        )
    }

    @Test
    fun `publish posts text and link and resolves the handle from users me`() {
        server.expect(requestTo("https://api.x.com/2/tweets"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.text").value("hello\n\nhttps://example.com/a"))
            .andRespond(
                withSuccess("""{"data":{"id":"1747047870031790080","text":"hello"}}""", MediaType.APPLICATION_JSON)
            )
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(
                withSuccess("""{"data":{"id":"2244994945","name":"Jane Doe","username":"janedoe"}}""", MediaType.APPLICATION_JSON)
            )

        val result = adapter.publish(creds, PublishCommand(title = "ignored", text = "hello", link = "https://example.com/a"))

        assertEquals("https://x.com/janedoe/status/1747047870031790080", result.externalUrl)
        assertNull(result.refreshedCredentials)
    }

    @Test
    fun `publish falls back to users me when the response carries no author`() {
        server.expect(requestTo("https://api.x.com/2/tweets"))
            .andRespond(
                withSuccess("""{"data":{"id":"123"}}""", MediaType.APPLICATION_JSON)
            )
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(
                withSuccess("""{"data":{"id":"1","name":"Jane Doe","username":"janedoe"}}""", MediaType.APPLICATION_JSON)
            )

        val result = adapter.publish(creds, PublishCommand(title = null, text = "hello"))

        assertEquals("https://x.com/janedoe/status/123", result.externalUrl)
    }

    @Test
    fun `a publish that refreshes and then fails still carries the rotation`() {
        server.expect(requestTo("https://api.x.com/2/tweets"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        expectRefresh("rtok")
        server.expect(requestTo("https://api.x.com/2/tweets"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok2"))
            .andRespond(
                withStatus(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"title":"Forbidden","detail":"Status is a duplicate.","status":403}""")
            )

        val e = assertFailsWith<PlatformException> {
            adapter.publish(creds, PublishCommand(title = null, text = "hello"))
        }
        assertEquals("X API error (403): Status is a duplicate.", e.message)
        assertEquals(mapOf("accessToken" to "tok2", "refreshToken" to "rtok2"), e.refreshedCredentials)
    }

    @Test
    fun `publish without a post id fails`() {
        server.expect(requestTo("https://api.x.com/2/tweets"))
            .andRespond(withSuccess("""{"data":{}}""", MediaType.APPLICATION_JSON))

        assertFailsWith<PlatformException> {
            adapter.publish(creds, PublishCommand(title = null, text = "hello"))
        }
    }

    @Test
    fun `X errors surface the detail field`() {
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(
                withStatus(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"title":"Forbidden","detail":"Your app does not have access to this endpoint.","status":403}""")
            )

        val e = assertFailsWith<PlatformException> { adapter.verify(creds) }
        assertEquals("X API error (403): Your app does not have access to this endpoint.", e.message)
    }

    @Test
    fun `network failures become PlatformException`() {
        server.expect(requestTo("https://api.x.com/2/users/me"))
            .andRespond(withServerError())

        assertFailsWith<PlatformException> { adapter.verify(creds) }
    }
}
