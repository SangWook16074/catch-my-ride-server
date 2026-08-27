# server/TODO.md — 서버 작업 목록

> 태스크 ID·완료 기준의 원천: [../TASK.md](../TASK.md) (S-x, P0-x) · [../DEPLOY.md](../DEPLOY.md) (D-x)
> 오너(🧑) 액션은 [../TODO.md](../TODO.md) — 여기는 에이전트가 서버 디렉토리에서 수행할 일만 둔다.
> **게이트**: Phase 1 본 개발(S-2~S-7)은 P0-5 오차 분석 + P0-7 템플릿 검수 통과 전에 시작하지 않는다.

## 지금 진행 가능 (키·오너 대기 없음)

- [x] **D-2** `Dockerfile` 작성 — multi-stage(gradle 빌드 → `eclipse-temurin:21-jre`), ARM64(Oracle VM)·로컬 양쪽 빌드 확인 — 2026-08-27 완료: 로컬(ARM Mac)에서 빌드·기동·healthy 확인
- [x] **D-3** `docker-compose.yml` + `Caddyfile` — caddy(HTTPS 자동) / app / postgres:17(볼륨 영속화) / redis(프로파일 정의만, 미기동) — 2026-08-27 완료: 루트 `docker-compose.yml` + `deploy/Caddyfile`. app 헬스체크 healthy·postgres 17.11 접속 실측 검증
- [x] **D-5** `.env.example` 작성 — `DATA_GO_KR_KEY`, `SEOUL_OPEN_DATA_KEY`, `POSTGRES_PASSWORD`, 앱인토스 파트너 키 자리. 실값 커밋 금지 — 2026-08-27 완료(관리 방식: 원본은 비밀번호 관리자, 로컬 `server/.env`, VM `/opt/catchmyride/.env` chmod 600)
- [x] **D-6** 배포 절차 문서화 — `git pull && docker compose up -d --build` 수동 배포 기준 — 2026-08-27 완료: [../deploy/README.md](../deploy/README.md) (VM 최초 셋업·평상시 배포·로컬 확인·운영)
- [x] **D-7** 운영 스크립트 — `pg_dump` 주기 백업 cron + `/actuator/health` 노출(UptimeRobot 감시용, actuator 의존성 추가) — 2026-08-27 완료: actuator 추가(테스트 `HealthEndpointTest`), `deploy/backup-db.sh`. cron 등록·UptimeRobot 설정은 VM 생성(D-1) 후

## 키 발급(P0-2)·타겟 확정(P0-3) 후

- [ ] `application.yml`의 `spike.targets` 채우기 — 오너가 알려준 노선의 arsId/stationId/역명
- [ ] 어댑터 3종 실호출 스모크 테스트 — 실제 응답으로 필드 가정 검증(TOPIS arrmsg/traTime, GBIS predictTime 분 단위, 지하철 barvlDt=0 케이스)
- [ ] **P0-4** 실측 로깅 기동 — 배포 VM(D-1 완료 시)에서 평일 06:30~09:30 폴링, `spike-logs/arrivals-*.jsonl` 5일치 이상
- [ ] **P0-5** 오차 분석 리포트 → `../docs/spike-report.md` — 소스별 예측 vs 실제 오차 분포, p90, 버퍼(1~3분) 흡수 가능 여부, 첫차·회차 이상치

## Phase 0 게이트 통과 후 (Phase 1 본편)

- [ ] **S-1** 도메인 모델·스키마 — `CommuteSetting`, `PushLog`(중복 발송 방어), `BoardingFeedback`. PostgreSQL 확정(마이그레이션 도구 포함)
- [ ] **S-2** 어댑터 본편화 — 스파이크 어댑터에서 `TransitAdapter` 인터페이스 추출 + 정류장 단위 캐시(TTL 15~30초, 단일 인스턴스는 인메모리 — DEPLOY.md §5)
- [ ] **S-3** 통근 설정 CRUD API — [../API.md](../API.md) §1 계약대로. 토스 로그인 토큰 검증→userKey 식별
- [ ] **S-4** 출발 타이밍 계산 서비스 — FR-301/303/304/407. **TDD 필수**(순수 로직이라 게이트 전에도 테스트 설계는 가능), 오너가 읽을 수 있게 명료하게
- [ ] **S-5** 알림 스케줄러 — 유저별 출근 시간대 폴링 → 2단계 푸시. 불변 조건: 1출근 2회 초과 발송 불가(PushLog로 강제), 미적용 요일 미발송
- [ ] **S-6** 앱인토스 `send-message` 클라이언트 — 승인된 templateSetCode + context 주입, 실패 재시도, 한도(앱 15,000회/분·유저 10회/분) 준수
- [ ] **S-7** 피드백 수집 API(API.md §3) + North Star 집계 쿼리(탑승 성공률·알림 정확도)

## 작업 수칙 (서버)

- 테스트 먼저(TDD) — 특히 S-4·S-5의 타이밍/불변 조건은 테스트가 명세다.
- Kotlin+Spring 명료함 유지, 과한 추상화·리액티브 전환 금지 (TASK.md 수칙 3)
- 키·시크릿은 env로만 (수칙 4)
