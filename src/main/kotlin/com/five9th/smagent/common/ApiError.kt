package com.five9th.smagent.common

import java.time.Instant

data class ApiError(
    val timestamp: Instant,
    val status: Int,
    val message: String?,
)

class NotFoundException(message: String) : RuntimeException(message)
