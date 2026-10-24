-- Time on the web app per account and local day, split into day time (06:00–22:00) and night time
-- (22:00–06:00) in the zone the heartbeat came from. Behind Settings → Screen time → Summary, the
-- daily limit and the weekly digest. See docs/user-settings.md.
--
-- Written only by an upsert that adds to both columns (ScreenTimeDayRepository.addSeconds), never
-- read-modify-write, so two tabs or a retried heartbeat cannot lose a count.
CREATE TABLE screen_time_days (
    id            BIGINT      PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    local_date    DATE        NOT NULL,
    day_seconds   INTEGER     NOT NULL DEFAULT 0,
    night_seconds INTEGER     NOT NULL DEFAULT 0,

    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    deleted_at    TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0
);

-- The upsert's conflict target, and what the week read walks.
CREATE UNIQUE INDEX uq_screen_time_days_user_date ON screen_time_days (user_id, local_date) WHERE deleted_at IS NULL;
