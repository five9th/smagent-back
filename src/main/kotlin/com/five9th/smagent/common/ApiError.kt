package com.five9th.smagent.common

import java.time.Instant

data class ApiError(
    val timestamp: Instant,
    val status: Int,
    val message: String?,
)

class NotFoundException(message: String) : RuntimeException(message)

/** Recognized but not implemented yet (e.g. deletePublications=true) -> 501. */
class NotImplementedException(message: String) : RuntimeException(message)
