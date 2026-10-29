package com.five9th.smagent.feedback

import com.five9th.smagent.common.NotFoundException
import com.five9th.smagent.domain.FeedbackItem
import com.five9th.smagent.repo.FeedbackItemRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Demo feedback list: standalone items a user can add, delete, check/uncheck.
 * Demo-only — no relations, no soft delete.
 */
@RestController
@RequestMapping("/api/feedback")
class FeedbackController(private val items: FeedbackItemRepository) {

    data class FeedbackDto(val id: Long?, val text: String, val checked: Boolean, val createdAt: Instant)
    data class CreateRequest(@field:NotBlank val text: String)
    data class UpdateRequest(val text: String? = null, val checked: Boolean? = null)

    @GetMapping
    fun list(): List<FeedbackDto> = items.findAllByOrderByCreatedAtDesc().map { toDto(it) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateRequest): FeedbackDto =
        toDto(items.save(FeedbackItem(text = request.text.trim())))

    @PatchMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateRequest): FeedbackDto {
        val item = items.findById(id).orElseThrow { NotFoundException("Feedback item $id not found") }
        request.text?.trim()?.takeIf { it.isNotEmpty() }?.let { item.text = it }
        request.checked?.let { item.checked = it }
        return toDto(items.save(item))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        val item = items.findById(id).orElseThrow { NotFoundException("Feedback item $id not found") }
        items.delete(item)
    }

    private fun toDto(item: FeedbackItem) =
        FeedbackDto(item.id, item.text, item.checked, item.createdAt)
}
