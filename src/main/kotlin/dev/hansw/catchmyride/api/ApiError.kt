package dev.hansw.catchmyride.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

/** API.md 공통 에러 응답: { "code": "...", "message": "..." } */
data class ErrorResponse(val code: String, val message: String)

/** 컨트롤러/서비스에서 던지면 ApiExceptionHandler가 공통 에러 응답으로 변환한다. */
class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message) {
    companion object {
        fun invalidRequest(message: String) = ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)
        fun upstreamUnavailable(message: String) = ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", message)
        fun settingNotFound() = ApiException(HttpStatus.NOT_FOUND, "SETTING_NOT_FOUND", "통근 설정이 없습니다")
        fun unauthorized() = ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다")
    }
}

/**
 * 클라이언트는 모든 비-2xx 바디의 code로 흐름을 분기한다(SERVER_FEEDBACK.md §2) —
 * 존재하지 않는 경로·본문 파싱 실패까지 전부 API.md 공통 에러 바디로 내려야 한다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.status).body(ErrorResponse(e.code, e.message))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("INVALID_REQUEST", "필수 파라미터 누락: ${e.parameterName}"))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("INVALID_REQUEST", "요청 본문을 해석할 수 없습니다"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleUnknownPath(e: NoResourceFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("NOT_FOUND", "존재하지 않는 경로입니다: /${e.resourcePath}"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("처리되지 않은 서버 오류", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", "서버 오류가 발생했습니다"))
    }
}
