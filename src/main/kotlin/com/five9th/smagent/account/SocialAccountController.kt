package com.five9th.smagent.account

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/social-accounts")
class SocialAccountController(private val service: SocialAccountService) {

    @GetMapping
    fun list(@RequestParam(defaultValue = "false") showDeleted: Boolean): List<AccountDto> =
        service.list(showDeleted)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateAccountRequest): AccountDto = service.create(request)

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): AccountDto = service.getDto(id)

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "false") mergeCredentials: Boolean,
        @Valid @RequestBody request: UpdateAccountRequest
    ): AccountDto =
        service.update(id, mergeCredentials, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = service.delete(id)

    @PostMapping("/{id}/verify")
    fun verify(@PathVariable id: Long): VerifyResponse = service.verify(id)
}
