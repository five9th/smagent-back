package com.five9th.smagent.common

import com.five9th.smagent.ai.adapter.AiProviderException
import com.five9th.smagent.platform.PlatformException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(e: NotFoundException): ApiError = ApiError(Instant.now(), 404, e.message)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(e: IllegalArgumentException): ApiError = ApiError(Instant.now(), 400, e.message)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun validation(e: MethodArgumentNotValidException): ApiError {
        val details = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ApiError(Instant.now(), 400, details.ifBlank { "Validation failed" })
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadable(@Suppress("UNUSED_PARAMETER") e: HttpMessageNotReadableException): ApiError =
        ApiError(Instant.now(), 400, "Malformed request body")

    @ExceptionHandler(NoResourceFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun noResource(@Suppress("UNUSED_PARAMETER") e: NoResourceFoundException): ApiError =
        ApiError(Instant.now(), 404, "No such endpoint")

    @ExceptionHandler(PlatformException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun platform(e: PlatformException): ApiError = ApiError(Instant.now(), 502, e.message)

    @ExceptionHandler(AiProviderException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun aiProvider(e: AiProviderException): ApiError = ApiError(Instant.now(), 502, e.message)

    @ExceptionHandler(NotImplementedException::class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    fun notImplemented(e: NotImplementedException): ApiError = ApiError(Instant.now(), 501, e.message)

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun unexpected(e: Exception): ApiError {
        log.error("Unhandled exception", e)
        return ApiError(Instant.now(), 500, "Internal error: ${e.message}")
    }
}
