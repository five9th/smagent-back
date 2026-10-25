package com.five9th.smagent.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Runtime app setting (key -> encrypted value). A stored row is an OVERRIDE of
 * the yaml/env default for that key — see SettingsService for resolution.
 */
@Entity
@Table(name = "app_settings")
class AppSetting(
    // column renamed away from `key` — reserved word in H2
    @Id
    @Column(name = "setting_key", length = 100)
    val key: String,

    /** AES-GCM encrypted value (CryptoService), like account credentials. */
    @Column(nullable = false, length = 4096)
    var valueEnc: String,
)
