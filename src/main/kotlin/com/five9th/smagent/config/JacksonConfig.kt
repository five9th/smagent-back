package com.five9th.smagent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.module.kotlin.KotlinModule

/**
 * Kotlin data-class (de)serialization for request/response bodies.
 * Boot 4 collects JacksonModule beans into the auto-configured JsonMapper.
 */
@Configuration
class JacksonConfig {

    @Bean
    fun kotlinModule(): KotlinModule = KotlinModule.Builder().build()
}
