package com.five9th.smagent.ai

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai")
class AiGenerateController(private val service: AiGenerateService) {

    @PostMapping("/generate")
    fun generate(@Valid @RequestBody request: GenerateRequest): GenerateResponse = service.generate(request)
}
