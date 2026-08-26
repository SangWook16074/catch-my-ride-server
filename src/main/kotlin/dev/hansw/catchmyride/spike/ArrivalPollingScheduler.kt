package dev.hansw.catchmyride.spike

import dev.hansw.catchmyride.spike.adapter.GbisBusAdapter
import dev.hansw.catchmyride.spike.adapter.SeoulSubwayAdapter
import dev.hansw.catchmyride.spike.adapter.TopisBusAdapter
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 0 실측 스케줄러: 설정된 출근 시간대(Asia/Seoul)에만 공공 API를 폴링해
 * 도착 예측 스냅샷을 JSONL로 축적한다. 본편의 알림 스케줄러 초안이기도 하다.
 */
@Component
class ArrivalPollingScheduler(
    private val props: SpikeProperties,
    private val topis: TopisBusAdapter,
    private val gbis: GbisBusAdapter,
    private val subway: SeoulSubwayAdapter,
    private val logWriter: ArrivalLogWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of("Asia/Seoul")
    private val warned = ConcurrentHashMap.newKeySet<String>()

    /** 폴링 윈도우 밖(예: 밤)에 서버를 띄워도 키 미설정을 바로 알 수 있게 시작 시점에 경고한다. */
    @EventListener(ApplicationReadyEvent::class)
    fun warnMissingKeysAtStartup() {
        if (props.keys.dataGoKr.isBlank()) {
            warnOnce("dataGoKr", "공공데이터포털 키(DATA_GO_KR_KEY)가 없어 버스 실측을 건너뜁니다")
        }
        if (props.keys.seoulOpenData.isBlank()) {
            warnOnce("seoulOpenData", "서울열린데이터광장 키(SEOUL_OPEN_DATA_KEY)가 없어 지하철 실측을 건너뜁니다")
        }
    }

    @Scheduled(fixedDelayString = "\${spike.polling.interval}", initialDelayString = "PT10S")
    fun poll() {
        val now = ZonedDateTime.now(zone)
        if (!isWithinWindow(now)) return

        if (props.keys.dataGoKr.isNotBlank()) {
            props.targets.seoulBus.forEach { id -> record("TOPIS", id) { topis.fetchArrivals(id) } }
            props.targets.gyeonggiBus.forEach { id -> record("GBIS", id) { gbis.fetchArrivals(id) } }
        }
        if (props.keys.seoulOpenData.isNotBlank()) {
            props.targets.subway.forEach { id -> record("SEOUL_SUBWAY", id) { subway.fetchArrivals(id) } }
        }
    }

    private fun record(source: String, stopId: String, fetch: () -> AdapterResult) {
        val collectedAt = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val record = try {
            val result = fetch()
            ArrivalLogRecord(collectedAt, source, stopId, ok = true, error = null,
                arrivals = result.arrivals, rawBody = result.rawBody.take(RAW_BODY_MAX_CHARS))
        } catch (e: Exception) {
            log.warn("{} {} 조회 실패: {}", source, stopId, e.message)
            ArrivalLogRecord(collectedAt, source, stopId, ok = false, error = e.message,
                arrivals = emptyList(), rawBody = null)
        }
        logWriter.write(record)
    }

    private fun isWithinWindow(now: ZonedDateTime): Boolean {
        if (props.polling.weekdaysOnly && now.dayOfWeek in WEEKEND) return false
        val time = now.toLocalTime()
        return !time.isBefore(LocalTime.parse(props.polling.windowStart)) &&
            !time.isAfter(LocalTime.parse(props.polling.windowEnd))
    }

    private fun warnOnce(key: String, message: String) {
        if (warned.add(key)) log.warn(message)
    }

    companion object {
        private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        private const val RAW_BODY_MAX_CHARS = 3000
    }
}
