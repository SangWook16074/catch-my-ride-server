package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * API.md §9-1 — 여정 CRUD. 구간 검증 규칙은 [JourneyLegValidator] (1회성 트립 시작과 공용).
 */
@RestController
class JourneyController(
    private val repository: JourneyRepository,
    private val trips: TripRepository,
    private val legValidator: JourneyLegValidator,
    private val userKeys: UserKeyResolver,
    private val clock: Clock,
) {

    data class JourneyRequest(val label: String?, val repeatDays: List<String>?, val legs: List<LegRequest>?)
    data class JourneyResponse(
        val id: String,
        val label: String,
        val repeatDays: List<String>,
        val legs: List<JourneyLeg>,
        val lastUsedAt: String?,
    )
    data class ListResponse(val journeys: List<JourneyResponse>)

    @GetMapping("/api/v1/journeys")
    fun list(@RequestHeader(value = "Authorization", required = false) auth: String?): ListResponse =
        ListResponse(repository.findAll(userKeys.resolve(auth)).map { it.toResponse() })

    @PostMapping("/api/v1/journeys")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @RequestBody request: JourneyRequest,
    ): JourneyResponse {
        val userKey = userKeys.resolve(auth)
        if (repository.count(userKey) >= MAX_JOURNEYS) {
            throw ApiException.invalidRequest("여정은 최대 ${MAX_JOURNEYS}개까지 저장할 수 있습니다")
        }
        val journey = validate(userKey, request, journeyId = UUID.randomUUID().toString())
        repository.insert(userKey, journey, LocalDateTime.now(clock))
        return journey.toResponse()
    }

    @PutMapping("/api/v1/journeys/{journeyId}")
    fun update(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable journeyId: String,
        @RequestBody request: JourneyRequest,
    ): JourneyResponse {
        val userKey = userKeys.resolve(auth)
        val existing = repository.find(userKey, journeyId) ?: throw ApiException.settingNotFound()
        val journey = validate(userKey, request, journeyId = journeyId, excludeId = journeyId)
            .copy(lastUsedAt = existing.lastUsedAt)
        repository.update(userKey, journey)
        return journey.toResponse()
    }

    @DeleteMapping("/api/v1/journeys/{journeyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable journeyId: String,
    ) {
        val userKey = userKeys.resolve(auth)
        if (repository.delete(userKey, journeyId) == 0) {
            throw ApiException.settingNotFound()
        }
        // 진행 중 트립이 이 여정이면 함께 종료 (§9-1)
        trips.deleteByJourney(userKey, journeyId)
    }

    private fun validate(userKey: String, request: JourneyRequest, journeyId: String, excludeId: String? = null): Journey {
        val label = request.label?.trim().orEmpty()
        if (label.isEmpty() || label.length > JOURNEY_LABEL_MAX_LENGTH) {
            throw ApiException.invalidRequest("여정 이름은 1~${JOURNEY_LABEL_MAX_LENGTH}자여야 합니다")
        }
        if (repository.labelExists(userKey, label, excludeId)) {
            throw ApiException.invalidRequest("같은 이름의 여정이 이미 있습니다: $label")
        }
        val repeatDays = request.repeatDays.orEmpty()
        if (repeatDays.any { it !in DAY_CODES }) {
            throw ApiException.invalidRequest("repeatDays는 MON~SUN이어야 합니다")
        }
        val legs = legValidator.validate(request.legs)
        return Journey(id = journeyId, label = label, repeatDays = repeatDays, legs = legs, lastUsedAt = null)
    }

    private fun Journey.toResponse() = JourneyResponse(
        id = id,
        label = label,
        repeatDays = repeatDays,
        legs = legs,
        lastUsedAt = lastUsedAt?.format(ISO),
    )

    companion object {
        private val DAY_CODES = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        private val ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }
}
