-- S-1 스키마 — PostgreSQL·H2(PostgreSQL 모드) 공용이라 방언 기능은 쓰지 않는다.
-- stops·active_days는 항상 통째로 읽고 쓰므로 JSON 문자열로 둔다 (오너 가독성 우선, 정규화는 필요해질 때).

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

-- FR-601 탑승 피드백 — 유저·날짜당 1건, 재제출은 갱신 (API.md §3)
CREATE TABLE IF NOT EXISTS boarding_feedback (
    user_key      VARCHAR(128) NOT NULL,
    notified_date DATE NOT NULL,
    result        VARCHAR(16) NOT NULL,           -- BOARDED | MISSED
    submitted_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (user_key, notified_date)
);
