package dev.hansw.catchmyride.push

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/** 스케줄러가 쓰는 시계 — 테스트에서 고정 시각으로 갈아끼우기 위한 빈 (시간 표기는 Asia/Seoul 고정, API.md 공통) */
@Configuration
class PushConfig {

    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}
