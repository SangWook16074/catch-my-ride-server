package dev.hansw.catchmyride.spike

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalDate
import java.time.ZoneId

/**
 * 실측 로그를 일자별 JSONL 파일로 남긴다: {log-dir}/arrivals-YYYYMMDD.jsonl
 * 스파이크 종료 후 이 파일들로 예측 오차 분포를 분석한다.
 */
@Component
class ArrivalLogWriter(
    private val objectMapper: ObjectMapper,
    private val props: SpikeProperties,
) {
    private val zone = ZoneId.of("Asia/Seoul")

    @Synchronized
    fun write(record: ArrivalLogRecord) {
        val dir = Path.of(props.logDir)
        Files.createDirectories(dir)
        val file = dir.resolve("arrivals-${LocalDate.now(zone).toString().replace("-", "")}.jsonl")
        Files.writeString(file, objectMapper.writeValueAsString(record) + "\n", CREATE, APPEND)
    }
}
