package dev.hansw.catchmyride.spike

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "spike")
data class SpikeProperties(
    val polling: Polling,
    val logDir: String,
    val keys: Keys,
    val targets: Targets,
) {
    data class Polling(
        val interval: Duration,
        val windowStart: String, // "06:30" — Asia/Seoul 기준
        val windowEnd: String,
        val weekdaysOnly: Boolean,
    )

    data class Keys(
        val dataGoKr: String,     // 공공데이터포털 Encoding 인증키 (TOPIS·GBIS 공용)
        val seoulOpenData: String, // 서울열린데이터광장 인증키 (지하철)
    )

    data class Targets(
        val seoulBus: List<String>,    // TOPIS arsId (정류소 번호 5자리)
        val gyeonggiBus: List<String>, // GBIS stationId
        val subway: List<String>,      // 지하철 역명
    )
}
