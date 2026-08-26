package dev.hansw.catchmyride.spike

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.hansw.catchmyride.spike.adapter.GbisBusAdapter
import dev.hansw.catchmyride.spike.adapter.SeoulSubwayAdapter
import dev.hansw.catchmyride.spike.adapter.TopisBusAdapter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P0-1 완료 기준 검증: 키가 없으면 bootRun 직후(폴링 윈도우 밖이어도)
 * "키 없음" 경고가 출력되어야 한다. 같은 경고는 한 번만 나온다.
 */
class ArrivalPollingSchedulerTest {

    private val logger = LoggerFactory.getLogger(ArrivalPollingScheduler::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
    }

    @Test
    fun `키가 둘 다 없으면 시작 시점에 경고 2건을 출력한다`() {
        val scheduler = schedulerWith(dataGoKr = "", seoulOpenData = "")

        scheduler.warnMissingKeysAtStartup()

        val warnings = appender.list.map { it.formattedMessage }
        assertTrue(warnings.any { "DATA_GO_KR_KEY" in it }, "버스 키 경고가 없음: $warnings")
        assertTrue(warnings.any { "SEOUL_OPEN_DATA_KEY" in it }, "지하철 키 경고가 없음: $warnings")
    }

    @Test
    fun `키가 있으면 경고를 출력하지 않는다`() {
        val scheduler = schedulerWith(dataGoKr = "some-key", seoulOpenData = "some-key")

        scheduler.warnMissingKeysAtStartup()

        assertEquals(emptyList(), appender.list.map { it.formattedMessage })
    }

    @Test
    fun `같은 키 경고는 여러 번 호출해도 한 번만 출력한다`() {
        val scheduler = schedulerWith(dataGoKr = "", seoulOpenData = "")

        scheduler.warnMissingKeysAtStartup()
        scheduler.warnMissingKeysAtStartup()

        assertEquals(2, appender.list.size, "경고가 중복 출력됨: ${appender.list.map { it.formattedMessage }}")
    }

    private fun schedulerWith(dataGoKr: String, seoulOpenData: String): ArrivalPollingScheduler {
        val props = SpikeProperties(
            polling = SpikeProperties.Polling(
                interval = Duration.ofSeconds(30),
                windowStart = "06:30",
                windowEnd = "09:30",
                weekdaysOnly = true,
            ),
            logDir = "./build/test-spike-logs",
            keys = SpikeProperties.Keys(dataGoKr = dataGoKr, seoulOpenData = seoulOpenData),
            targets = SpikeProperties.Targets(seoulBus = emptyList(), gyeonggiBus = emptyList(), subway = emptyList()),
        )
        val objectMapper = JsonMapper.builder().build()
        return ArrivalPollingScheduler(
            props = props,
            topis = TopisBusAdapter(props),
            gbis = GbisBusAdapter(props, objectMapper),
            subway = SeoulSubwayAdapter(props, objectMapper),
            logWriter = ArrivalLogWriter(objectMapper, props),
        )
    }
}
