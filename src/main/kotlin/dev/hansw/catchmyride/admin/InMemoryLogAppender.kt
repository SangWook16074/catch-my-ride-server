package dev.hansw.catchmyride.admin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 최근 로그를 인메모리 링버퍼에 보관해 /api/admin/logs(모니터링 대시보드)로 조회할 수 있게 한다.
 *
 * 버퍼 두 개를 따로 둔다 — 전체(INFO+)는 RequestTimingFilter의 요청 라인 등으로 빨리 밀려나가므로,
 * WARN/ERROR는 별도 버퍼에 남겨 에러가 트래픽 로그에 묻혀 유실되지 않게 한다.
 * 수치·메트릭과 마찬가지로 재배포하면 리셋된다. 전체 이력은 여전히 docker logs가 원본.
 */
data class LogEntry(
    val seq: Long,
    val timestamp: String,
    val level: String,
    val logger: String,
    val thread: String,
    val message: String,
    val stacktrace: String?,
)

object LogBuffer {
    private const val RECENT_CAPACITY = 1000
    private const val ERROR_CAPACITY = 500
    private const val STACKTRACE_MAX_CHARS = 8000

    private val seq = AtomicLong()
    private val lock = Any()
    private val recent = ArrayDeque<LogEntry>(RECENT_CAPACITY)
    private val errors = ArrayDeque<LogEntry>(ERROR_CAPACITY)

    fun add(event: ILoggingEvent) {
        val entry = LogEntry(
            seq = seq.incrementAndGet(),
            timestamp = Instant.ofEpochMilli(event.timeStamp).toString(),
            level = event.level.toString(),
            logger = event.loggerName,
            thread = event.threadName,
            message = event.formattedMessage ?: "",
            stacktrace = event.throwableProxy
                ?.let { ThrowableProxyUtil.asString(it).take(STACKTRACE_MAX_CHARS) },
        )
        synchronized(lock) {
            recent.addLast(entry)
            if (recent.size > RECENT_CAPACITY) recent.removeFirst()
            if (event.level.isGreaterOrEqual(Level.WARN)) {
                errors.addLast(entry)
                if (errors.size > ERROR_CAPACITY) errors.removeFirst()
            }
        }
    }

    /** 최신순으로 최대 [limit]건. WARN 이상 조회는 전용 버퍼를 봐서 INFO 홍수에 밀려난 에러도 남는다. */
    fun query(minLevel: Level, limit: Int, q: String?): List<LogEntry> {
        val source = if (minLevel.isGreaterOrEqual(Level.WARN)) errors else recent
        val needle = q?.lowercase()
        synchronized(lock) {
            val out = ArrayList<LogEntry>(limit)
            val it = source.descendingIterator()
            while (it.hasNext() && out.size < limit) {
                val e = it.next()
                if (!Level.toLevel(e.level, Level.INFO).isGreaterOrEqual(minLevel)) continue
                if (needle != null &&
                    !e.message.lowercase().contains(needle) &&
                    !e.logger.lowercase().contains(needle) &&
                    e.stacktrace?.lowercase()?.contains(needle) != true
                ) continue
                out.add(e)
            }
            return out
        }
    }
}

class InMemoryLogAppender : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) = LogBuffer.add(event)
}

/**
 * 루트 로거에 어펜더를 부착한다. 스프링 컨텍스트 초기화 이후부터 잡히므로
 * 기동 극초반(컨텍스트 리프레시 전) 로그는 버퍼에 없다 — 그 구간은 docker logs로 본다.
 */
@Configuration
class LogCaptureConfig {

    @PostConstruct
    fun attachAppender() {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        if (root.getAppender(APPENDER_NAME) != null) return // 테스트의 다중 컨텍스트 중복 부착 방지
        val appender = InMemoryLogAppender().apply {
            name = APPENDER_NAME
            setContext(context)
            start()
        }
        root.addAppender(appender)
    }

    companion object {
        private const val APPENDER_NAME = "IN_MEMORY_ADMIN"
    }
}
