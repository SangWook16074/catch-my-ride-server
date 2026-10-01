package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.push.ApnsClient
import dev.hansw.catchmyride.push.ApnsProperties
import dev.hansw.catchmyride.push.ApnsTokenInvalidException
import dev.hansw.catchmyride.push.FcmPushClient
import dev.hansw.catchmyride.push.PushProperties
import dev.hansw.catchmyride.push.PushToken
import dev.hansw.catchmyride.push.PushTokenRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * API.md §9-5 "진행 표면 원격 갱신" (v0.12) — 값이 바뀔 때만 보내고(같은 값 반복 금지),
 * 분당 6건 상한, 토큰 만료 시 폐기, DONE은 iOS에 end 이벤트를 보내는지 검증한다.
 * 하차·환승 알림(§9-4)과는 별개 채널이라 trip_push_log는 건드리지 않는다.
 */
@SpringBootTest
class TripSurfaceUpdateTest {

    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var surfaceTokens: TripSurfaceTokenRepository
    @Autowired lateinit var surfaceStates: TripSurfaceStateRepository
    @Autowired lateinit var pushTokens: PushTokenRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val apns = RecordingApns()
    private val fcm by lazy { RecordingFcmSurface(pushTokens) }
    private val updater by lazy { TripSurfaceUpdater(trips, surfaceTokens, surfaceStates, pushTokens, apns, fcm) }

    private val legs = listOf(JourneyLeg("SUBWAY", "9호선 급행", "여의도", "당산"))

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM trip").update()
        jdbc.sql("DELETE FROM trip_surface_token").update()
        jdbc.sql("DELETE FROM trip_surface_state").update()
        jdbc.sql("DELETE FROM push_token").update()
        apns.reset()
        fcm.reset()
    }

    private fun insertTrip(remainingStops: Int? = 4, currentStop: String? = "선유도", phase: TripPhase = TripPhase.TRACKING): Trip {
        val now = LocalDateTime.now()
        val trip = Trip(
            tripId = "trip-surface", userKey = "dev-user", journeyId = null, legs = legs,
            legIndex = 0, phase = phase, btrainNo = "9027", candidates = listOf("9027"),
            remainingStops = remainingStops, currentStop = currentStop, realtimeAvailable = true,
            legStartedAt = now, lastSeenAt = now, startedAt = now,
        )
        trips.insert(trip, now)
        return trip
    }

    @Test
    fun `값이 바뀌면 보내고 같은 값 반복은 보내지 않는다`() {
        insertTrip(remainingStops = 4, currentStop = "선유도")
        surfaceTokens.upsert("trip-surface", "IOS", "activity-token-1", LocalDateTime.now())
        val now = LocalDateTime.now()

        updater.afterTick("trip-surface", now) // 첫 호출 — 직전 기록이 없으니 보낸다
        assertEquals(1, apns.updates.size)

        updater.afterTick("trip-surface", now) // 같은 값 — 반복 발송 금지
        assertEquals(1, apns.updates.size)

        // 값이 바뀌면 다시 보낸다
        trips.save(trips.find("trip-surface")!!.copy(remainingStops = 3, currentStop = "국회의사당"), now)
        updater.afterTick("trip-surface", now)
        assertEquals(2, apns.updates.size)
        assertEquals(3, apns.updates.last().second["remainingStops"])
    }

    @Test
    fun `분당 6건을 넘기면 이번 변화는 건너뛴다`() {
        insertTrip(remainingStops = 10)
        surfaceTokens.upsert("trip-surface", "IOS", "activity-token-1", LocalDateTime.now())
        val base = LocalDateTime.now()

        for (i in 10 downTo 1) {
            trips.save(trips.find("trip-surface")!!.copy(remainingStops = i), base)
            updater.afterTick("trip-surface", base) // 같은 1분 윈도우 — now를 고정
        }

        // 최초 1회(state==null) + 값이 바뀐 9번 중 5번만 더 — 합계 6건, 나머지는 상한에 걸려 건너뛴다
        assertEquals(6, apns.updates.size)
    }

    @Test
    fun `토큰이 만료되면(410) 폐기한다`() {
        insertTrip()
        surfaceTokens.upsert("trip-surface", "IOS", "dead-token", LocalDateTime.now())
        apns.failWith = ApnsTokenInvalidException()

        updater.afterTick("trip-surface", LocalDateTime.now())

        assertNull(surfaceTokens.find("trip-surface"))
    }

    @Test
    fun `DONE이면 iOS에 end 이벤트를 보낸다`() {
        insertTrip(remainingStops = 0, phase = TripPhase.DONE)
        surfaceTokens.upsert("trip-surface", "IOS", "activity-token-1", LocalDateTime.now())

        updater.afterTick("trip-surface", LocalDateTime.now())

        assertEquals(1, apns.ends.size)
        assertTrue(apns.updates.isEmpty())
    }

    @Test
    fun `트립 종료(notifyEnd) 시에도 end를 보내고 상태를 지운다`() {
        val trip = insertTrip()
        surfaceTokens.upsert("trip-surface", "IOS", "activity-token-1", LocalDateTime.now())
        updater.afterTick("trip-surface", LocalDateTime.now()) // 상태 하나 기록

        updater.notifyEnd(trip, LocalDateTime.now())

        assertEquals(1, apns.ends.size)
        assertNull(surfaceStates.find("trip-surface"))
    }

    @Test
    fun `Android는 push_token(ANDROID)으로 데이터 전용 메시지를 보낸다`() {
        insertTrip(remainingStops = 2, currentStop = "당산")
        pushTokens.upsert("dev-user", "fcm-token", "ANDROID")

        updater.afterTick("trip-surface", LocalDateTime.now())

        assertEquals(1, fcm.sent.size)
        val data = fcm.sent.first()
        assertEquals("TRIP_SURFACE", data["type"])
        assertEquals("trip-surface", data["tripId"])
        assertEquals("2", data["remainingStops"])
        assertEquals("당산", data["currentStop"])
    }

    @Test
    fun `iOS 유저는 push_token(ANDROID 전용 FCM 표면 발송)을 받지 않는다`() {
        insertTrip()
        pushTokens.upsert("dev-user", "fcm-token", "IOS")

        updater.afterTick("trip-surface", LocalDateTime.now())

        assertTrue(fcm.sent.isEmpty())
    }
}

private class RecordingApns : ApnsClient(ApnsProperties()) {
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    val ends = mutableListOf<String>()
    var failWith: RuntimeException? = null

    fun reset() {
        updates.clear()
        ends.clear()
        failWith = null
    }

    override fun sendUpdate(activityToken: String, contentState: Map<String, Any?>, now: java.time.Instant): Boolean {
        failWith?.let { throw it }
        updates.add(activityToken to contentState)
        return true
    }

    override fun sendEnd(activityToken: String, contentState: Map<String, Any?>, now: java.time.Instant): Boolean {
        failWith?.let { throw it }
        ends.add(activityToken)
        return true
    }
}

private class RecordingFcmSurface(tokens: PushTokenRepository) : FcmPushClient(PushProperties(), tokens) {
    val sent = mutableListOf<Map<String, String>>()

    fun reset() {
        sent.clear()
    }

    override fun sendDataMessage(userKey: String, token: PushToken, data: Map<String, String>): Boolean {
        sent.add(data)
        return true
    }
}
