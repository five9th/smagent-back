package com.five9th.smagent.ai

import com.five9th.smagent.domain.GenerateEffort
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai")
class AiGenerateController(private val service: AiGenerateService) {

    @PostMapping("/generate")
    fun generate(@Valid @RequestBody request: GenerateRequest): GenerateResponse = service.generate(request)

    /**
     * Effort levels for the SPA's picker — a stub returning the full enum for
     * every model. Later this can map a model to the values it actually
     * supports (query params would be additive).
     */
    @GetMapping("/effort-levels")
    fun effortLevels(): List<GenerateEffort> = GenerateEffort.entries.toList()
}
