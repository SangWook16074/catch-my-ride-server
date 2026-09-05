-- S-1 스키마 — PostgreSQL·H2(PostgreSQL 모드) 공용이라 방언 기능은 쓰지 않는다.
-- stops·active_days는 항상 통째로 읽고 쓰므로 JSON 문자열로 둔다 (오너 가독성 우선, 정규화는 필요해질 때).
-- sql.init.mode=always로 부팅마다 실행되므로 모든 문장은 멱등이어야 한다.

-- (레거시) 유저당 1개 시절의 테이블 — commute_route로 이관됐다. 이관 원본으로 당분간 보존, 새 코드는 읽지 않는다.
CREATE TABLE IF NOT EXISTS commute_setting (
    user_key             VARCHAR(128) PRIMARY KEY,
    home_latitude        DOUBLE PRECISION NOT NULL,
    home_longitude       DOUBLE PRECISION NOT NULL,
    stops_json           TEXT NOT NULL,           -- CommuteSetting.stops 배열의 JSON 직렬화
    walk_minutes         INT NOT NULL,
    notification_mode    VARCHAR(16) NOT NULL,    -- FIXED | RECOMMENDED
    fixed_departure_time VARCHAR(5),              -- "HH:mm" (FIXED일 때만)
    window_start         VARCHAR(5),              -- (RECOMMENDED일 때만)
    window_end           VARCHAR(5),
    buffer_minutes       INT NOT NULL,
    active_days_json     TEXT NOT NULL,           -- ["MON",...] JSON 직렬화
    updated_at           TIMESTAMP NOT NULL
);

-- 유저당 여러 경로(출근·퇴근·자유 라벨, 최대 5개 — 상한은 서버 검증) — 각 경로가 독립된 통근 설정을 가진다
CREATE TABLE IF NOT EXISTS commute_route (
    user_key             VARCHAR(128) NOT NULL,
    route_id             VARCHAR(36) NOT NULL,    -- 서버 발급 UUID (이관 행은 'migrated')
    label                VARCHAR(32) NOT NULL,    -- "출근"/"퇴근"/자유 입력
    enabled              BOOLEAN NOT NULL,        -- false = 이 경로 알림 일시 중지
    home_latitude        DOUBLE PRECISION NOT NULL,
    home_longitude       DOUBLE PRECISION NOT NULL,
    stops_json           TEXT NOT NULL,
    walk_minutes         INT NOT NULL,
    notification_mode    VARCHAR(16) NOT NULL,
    fixed_departure_time VARCHAR(5),
    window_start         VARCHAR(5),
    window_end           VARCHAR(5),
    buffer_minutes       INT NOT NULL,
    active_days_json     TEXT NOT NULL,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    CONSTRAINT commute_route_pkey PRIMARY KEY (user_key, route_id)
);

-- 이관: 기존 단일 설정 → '출근' 경로. 경로가 하나도 없는 유저만 대상이라 재부팅마다 실행돼도 안전하다.
INSERT INTO commute_route
  (user_key, route_id, label, enabled, home_latitude, home_longitude, stops_json, walk_minutes,
   notification_mode, fixed_departure_time, window_start, window_end, buffer_minutes, active_days_json,
   created_at, updated_at)
SELECT cs.user_key, 'migrated', '출근', TRUE, cs.home_latitude, cs.home_longitude, cs.stops_json, cs.walk_minutes,
       cs.notification_mode, cs.fixed_departure_time, cs.window_start, cs.window_end, cs.buffer_minutes, cs.active_days_json,
       cs.updated_at, cs.updated_at
FROM commute_setting cs
WHERE NOT EXISTS (SELECT 1 FROM commute_route r WHERE r.user_key = cs.user_key);

-- FR-403 출근 1회당 최대 2회(PRE·REMIND) — PK가 (경로×날짜×스테이지) 중복 발송을 구조적으로 막는다 (S-5)
CREATE TABLE IF NOT EXISTS push_log (
    user_key      VARCHAR(128) NOT NULL,
    route_id      VARCHAR(36) NOT NULL,
    notified_date DATE NOT NULL,
    stage         VARCHAR(16) NOT NULL,   -- PRE | REMIND
    route_name    VARCHAR(64),
    delivered     BOOLEAN NOT NULL,       -- false = dry-run 기록 (앱인토스 키·템플릿 미설정 상태)
    sent_at       TIMESTAMP NOT NULL,
    CONSTRAINT push_log_pkey PRIMARY KEY (user_key, route_id, notified_date, stage)
);

-- 기존 push_log(경로 개념 이전) 이관 — 컬럼 추가 후 PK를 경로 포함으로 교체 (drop+add 쌍이라 멱등)
ALTER TABLE push_log ADD COLUMN IF NOT EXISTS route_id VARCHAR(36) DEFAULT 'migrated' NOT NULL;
ALTER TABLE push_log DROP CONSTRAINT IF EXISTS push_log_pkey;
ALTER TABLE push_log ADD CONSTRAINT push_log_pkey PRIMARY KEY (user_key, route_id, notified_date, stage);

-- FR-601 탑승 피드백 — 유저·날짜당 1건, 재제출은 갱신 (API.md §3). 경로 구분은 푸시 랜딩에 routeId가 실리면 추가.
CREATE TABLE IF NOT EXISTS boarding_feedback (
    user_key      VARCHAR(128) NOT NULL,
    notified_date DATE NOT NULL,
    result        VARCHAR(16) NOT NULL,           -- BOARDED | MISSED
    submitted_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (user_key, notified_date)
);
