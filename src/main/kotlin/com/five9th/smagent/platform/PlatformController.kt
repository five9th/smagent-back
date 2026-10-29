package com.five9th.smagent.platform

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Read-only catalog of platforms and their login options. */
@RestController
@RequestMapping("/api/platforms")
class PlatformController(private val registry: AdapterRegistry) {

    @GetMapping
    fun list(): List<PlatformDto> = registry.platformInfos()
}
