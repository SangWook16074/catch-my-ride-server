# PERFORMANCE.md — 성능 최적화 기록

> 규칙: 성능 최적화 작업은 반드시 여기에 기록한다 — 무엇을 어떻게 개선했고, 몇 초에서 몇 초가 되었는지.
> 서버 실측 도구: `RequestTimingFilter` 로그(개별 요청), `/actuator/metrics/http.server.requests`(URI별 누적),
> `/api/admin/monitoring`(p50/p95/p99, ADMIN_TOKEN 필요).

## 2026-09-05 — "현재 위치로 등록" 응답 지연

### 증상

온보딩 집 위치 단계에서 "현재 위치로 등록"을 누르면 응답이 엄청 오래 걸림 (수 초~수십 초 체감).

### 조사 — 서버는 병목이 아니었다 (실측)

| 구간 | 실측치 | 방법 |
|---|---|---|
| `PUT /api/v1/commute-setting` (프로덕션, 서버 내부 처리) | 평균 **24ms** (9건 누적) | `/actuator/metrics/http.server.requests?tag=uri:...&tag=method:PUT` |
| `GET /api/v1/commute-setting` (프로덕션) | 평균 **2.4ms** (553건) | 위와 동일 |
| PUT 로컬 재현 (Postgres 실기동, anon 키 15회) | **6~10ms** (첫 요청만 110ms) | curl `time_total` |
| 프로덕션 왕복 전체 (TLS 포함, 이 Mac → catchmyride.hansw.dev) | **32~40ms** | curl (dns 3ms / tls 27ms / ttfb 35ms) |
| `GET /api/v1/map-preview` (등록 직후 지도 미리보기) | 평균 133ms (22건) | metrics |
| `GET /api/v1/geocode` | 평균 126ms (8건) | metrics |

서버의 등록 경로(인증 resolve → 검증 → delete+insert 업서트)는 어디서 재도 ms 단위. 상류를 타는 지도
프록시 2종도 130ms 수준이라 "엄청 오래"의 원인이 될 수 없음.

### 근본 원인 — 클라이언트 기기 GPS 대기

앱의 "현재 위치로 등록" 버튼 스피너가 감싸는 유일한 await는 서버 호출이 아니라
`getCurrentLocation({ accuracy: Accuracy.High })` (앱인토스 SDK, `catch_my_ride_appintoss/src/pages/onboarding.tsx`).

- `Accuracy.High` = 오차 10m 이내 → 실제 GPS 위성 픽스를 요구. 실내·콜드 스타트에서는 픽스까지
  수십 초가 걸리거나 끝내 실패하는데, SDK `getCurrentLocation`에는 타임아웃 옵션이 없어 그 시간을 통째로 기다렸다.
- 코드에도 "P0-8 잔여: Accuracy.High의 실제 응답 속도 확인"이라는 미해결 메모가 있었음 — 이번에 확인·해소.

### 개선 — High 3초 상한 + Balanced 강등 레이스

`catch_my_ride_appintoss/src/pages/onboarding.tsx` `requestLocation()`:

1. `Accuracy.High` 요청을 걸고 **3초(`HIGH_ACCURACY_WAIT_MS`)까지만** 기다린다.
2. 3초 안에 오면 그대로 사용 (야외 등 정상 케이스는 동작 변화 없음).
3. 늦으면 `Accuracy.Balanced`(오차 수백 m, WiFi/기지국 기반이라 보통 1초 내) 요청을 추가로 걸고,
   High vs Balanced 중 **먼저 도착하는 쪽**을 사용한다.

정확도 트레이드오프: Balanced로 강등되면 마커가 수백 m 어긋날 수 있으나, 등록 화면에 지도 미리보기 확인 +
"주소로 등록" 폴백(FR-101 보완)이 이미 있어 사용자가 즉시 교정 가능. 집 위치는 지도 표시용이고
도보 시간은 유저 직접 입력이라 서비스 로직 정확도에는 영향 없음.

### 결과

- **개선 전**: GPS 픽스 시간 = 대기 시간, 상한 없음 — 실내에서 수십 초 ~ 무한 대기.
- **개선 후**: 최악 케이스 상한 **약 3~4초** (High 3초 컷 + Balanced 응답 ~1초). GPS가 3초 내에 잡히는
  정상 케이스는 기존과 동일하게 즉시 완료.
- 서버 측 변경 없음 (병목 아님 실측 확인).
- ⚠️ 기기별 실제 수치는 샌드박스 실기기 실측 필요(P0-8과 함께) — 위 "개선 후" 수치는 설계상 상한이고,
  서버 수치만 실측치다.
