package com.five9th.smagent.crypto

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for credential blobs. Output format: base64(iv[12] || ciphertext+tag).
 *
 * Key resolution order:
 *  1. `smagent.security.encryption-key` (env `SMAGENT_ENCRYPTION_KEY`, base64 of 32 bytes)
 *  2. key file (`smagent.security.key-file`), generated on first start, owner-only permissions
 */
@Service
class CryptoService(
    @Value("\${smagent.security.encryption-key:}") private val envKeyB64: String,
    @Value("\${smagent.security.key-file:./data/smagent.key}") private val keyFile: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val key: SecretKey = resolveKey()

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(encoded: String): String {
        val blob = Base64.getDecoder().decode(encoded)
        require(blob.size > IV_LENGTH_BYTES) { "Encrypted blob too short" }
        val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun resolveKey(): SecretKey {
        val envKey = envKeyB64.trim()
        if (envKey.isNotEmpty()) {
            val bytes = try {
                Base64.getDecoder().decode(envKey)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("SMAGENT_ENCRYPTION_KEY is not valid base64", e)
            }
            require(bytes.size == KEY_LENGTH_BYTES) {
                "SMAGENT_ENCRYPTION_KEY must decode to $KEY_LENGTH_BYTES bytes, got ${bytes.size}"
            }
            return SecretKeySpec(bytes, "AES")
        }

        val path = Path.of(keyFile)
        if (Files.exists(path)) {
            val bytes = Base64.getDecoder().decode(Files.readString(path).trim())
            require(bytes.size == KEY_LENGTH_BYTES) {
                "Key file $keyFile must contain a base64 $KEY_LENGTH_BYTES-byte key"
            }
            return SecretKeySpec(bytes, "AES")
        }

        val bytes = ByteArray(KEY_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.writeString(path, Base64.getEncoder().encodeToString(bytes))
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } // non-POSIX filesystems (Windows) don't support this; best effort
        log.info("Generated new encryption key file at {} (keep it secret; back it up with the database)", path.toAbsolutePath())
        return SecretKeySpec(bytes, "AES")
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_LENGTH_BYTES = 32
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private val secureRandom = SecureRandom()
    }
}
