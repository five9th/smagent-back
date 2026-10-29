package com.five9th.smagent.crypto

import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CryptoServiceTest {

    private fun randomEnvKey(): String =
        Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

    @Test
    fun `roundtrips plaintext`() {
        val service = CryptoService(envKeyB64 = randomEnvKey(), keyFile = Files.createTempDirectory("smagent").resolve("k.key").toString())
        val secret = """{"pageId":"123","pageAccessToken":"EAABxyz"}"""
        assertEquals(secret, service.decrypt(service.encrypt(secret)))
    }

    @Test
    fun `same plaintext encrypts to different ciphertexts (random IV)`() {
        val service = CryptoService(envKeyB64 = randomEnvKey(), keyFile = Files.createTempDirectory("smagent").resolve("k.key").toString())
        val a = service.encrypt("same")
        val b = service.encrypt("same")
        assertNotEquals(a, b)
        assertEquals("same", service.decrypt(a))
        assertEquals("same", service.decrypt(b))
    }

    @Test
    fun `rejects wrong key with exception`() {
        val dir = Files.createTempDirectory("smagent").toString()
        val encryptor = CryptoService(envKeyB64 = randomEnvKey(), keyFile = "$dir/a.key")
        val other = CryptoService(envKeyB64 = randomEnvKey(), keyFile = "$dir/b.key")
        val blob = encryptor.encrypt("secret")
        assertFailsWith<Exception> { other.decrypt(blob) }
    }

    @Test
    fun `generates and reuses key file when env key absent`() {
        val dir = Files.createTempDirectory("smagent")
        val keyFile = dir.resolve("smagent.key")
        val first = CryptoService(envKeyB64 = "", keyFile = keyFile.toString())
        val blob = first.encrypt("persisted")
        // second instance on same key file must decrypt it
        val second = CryptoService(envKeyB64 = "", keyFile = keyFile.toString())
        assertEquals("persisted", second.decrypt(blob))
    }

    @Test
    fun `rejects env key of wrong length`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFailsWith<IllegalArgumentException> {
            CryptoService(envKeyB64 = short, keyFile = Files.createTempDirectory("smagent").resolve("k.key").toString())
        }
    }
}
