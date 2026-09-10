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

## 2026-09-10 — "현재 위치로 등록" 실패율·지연 2차 개선

### 증상

오너 리포트: 현재 위치 등록 실패율이 여전히 높고 느리다.

### 원인 분석 (코드 경로 추적)

1. **직렬 강등의 3초 낭비**: Balanced(와이파이·기지국, 보통 ~1초) 요청을 "High가 3초를 넘긴 뒤"에야
   시작했다 — 실내(High가 어차피 못 잡는 환경)에서 매번 3초 + Balanced 응답을 그냥 기다림.
2. **High 조기 거부 시 전면 실패 (실패율 주범)**: `Promise.race([high, 3초타이머])`는 거부도 전파한다 —
   High가 3초 안에 에러로 죽으면 race 전체가 거부돼 **Balanced를 시도조차 않고** 실패 처리됐다.
   재시도 1회도 같은 경로라 실내에서는 반복 실패.
3. **역지오코딩이 스피너를 잡음**: 등록 후 주소 표시(§7-1) 조회를 await로 기다려 버튼 로딩이
   측위+α로 길어짐 (서버 실측 ~130ms + 왕복).
4. (배포 요인) 2026-09-09 권한 다이얼로그/타임아웃 분리 수정이 아직 유저 번들에 미배포 —
   구번들 유저는 "최초 권한 요청 = 무조건 1회 실패"를 계속 겪는 중.

### 개선 (catch_my_ride_appintoss/src/pages/onboarding.tsx)

- High·Balanced를 **처음부터 동시 요청**. 3초까지는 High 우선(정확도), High가 먼저 실패하면
  즉시 Balanced로 — `firstSuccess`(전부 실패할 때만 거부)로 한쪽 에러가 전체를 죽이지 않는다.
- 역지오코딩은 백그라운드 조회로 전환 — 스피너는 측위까지만.
- 관측 추가: `onboarding_location_result`에 `duration_ms`·`source`(high/balanced) — 이후 실패
  원인·지연 분포를 데이터로 판단.

### 결과 (설계상 기대치 — 실기기 실측은 배포 후 이벤트로)

- 실내 성공 경로: 기존 3초+Balanced응답 → **Balanced 응답 즉시(~1초)** (High 조기 실패 시).
- 실내 실패율: "High 조기 에러 = 전면 실패" 경로 제거 — Balanced까지 죽어야 실패.
- 스피너 시간: 역지오코딩 왕복만큼 단축.
