-- Account-level settings behind the client's /setting page. A separate table rather than columns
-- on user_profiles: GET /users?ids= batches a hundred profiles at a time on hot paths, and only
-- the owner ever reads these.
--
-- No row is written at registration. A read with no row answers the defaults below and a write
-- with no row inserts one, so this table never races UserRegisteredEvent the way user_profiles
-- does. The DEFAULT clauses mirror UserSettings.defaultsFor() — keep the two in step.
CREATE TABLE user_settings (
    id                          BIGINT       PRIMARY KEY,
    user_id                     BIGINT       NOT NULL,

    comment_audience            VARCHAR(16)  NOT NULL DEFAULT 'EVERYONE',
    dm_audience                 VARCHAR(16)  NOT NULL DEFAULT 'EVERYONE',

    -- Null means off. There is no separate enable flag, and 0 is rejected rather than being a
    -- second way to say off.
    screen_time_limit_minutes   INTEGER,
    -- Wall-clock, no timezone: no account on this platform has one, so these can only mean
    -- "22:30 wherever the viewer is" and are enforced by the client. See docs/user-settings.md §2.1.
    sleep_hours_start           TIME,
    sleep_hours_end             TIME,
    weekly_screen_time_digest   BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    deleted_at                  TIMESTAMPTZ,
    version                     BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_settings_user_id ON user_settings (user_id) WHERE deleted_at IS NULL;
