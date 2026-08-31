package dev.hansw.catchmyride

import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** HTTP 계약 테스트 공용 헬퍼 — 실제 포트로 요청해 직렬화·에러 바디까지 통으로 검증한다. */
object ApiContractTestSupport {

    fun request(
        environment: Environment,
        method: String,
        path: String,
        jsonBody: String? = null,
    ): HttpResponse<String> {
        val port = environment.getProperty("local.server.port")
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        when (jsonBody) {
            null -> builder.method(method, HttpRequest.BodyPublishers.noBody())
            else -> builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
