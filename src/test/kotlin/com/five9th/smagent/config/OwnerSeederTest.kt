package com.five9th.smagent.config

import com.five9th.smagent.repo.AppUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@Transactional // rollback: tests rewrite the owner's password, must not leak into each other
class OwnerSeederTest(
    @Autowired val users: AppUserRepository,
    @Autowired val encoder: PasswordEncoder,
) {
    // test profile seeds owner 'test-owner' / 'test-password' at context startup

    private fun restartWith(username: String, password: String) =
        OwnerSeeder(users, encoder, adminUsername = username, adminPassword = password).seed()

    @Test
    fun `new env password on restart updates the stored hash`() {
        restartWith("test-owner", "new-password") // "restart" with changed SMAGENT_ADMIN_PASSWORD

        val owner = users.findByUsername("test-owner")!!
        assertTrue(encoder.matches("new-password", owner.passwordHash), "env password must win")
        assertFalse(encoder.matches("test-password", owner.passwordHash), "old password must no longer match")

        // idempotent: re-running with the same env password must not re-hash
        val hashBefore = owner.passwordHash
        restartWith("test-owner", "new-password")
        assertTrue(users.findByUsername("test-owner")!!.passwordHash == hashBefore)
    }

    @Test
    fun `blank env password never wipes the existing one`() {
        restartWith("test-owner", "")
        assertTrue(encoder.matches("test-password", users.findByUsername("test-owner")!!.passwordHash))
    }

    @Test
    fun `different env username does not create a second owner`() {
        restartWith("intruder", "whatever")
        assertTrue(users.count() == 1L, "no second account may be created")
        assertNull(users.findByUsername("intruder"))
    }
}
