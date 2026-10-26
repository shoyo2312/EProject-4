-- Daily screen time, rebuilt as on TikTok: the same limit every day (screen_time_limit_minutes) or
-- one per weekday, and a 4-digit passcode to keep using the app past it. See docs/user-settings.md.
ALTER TABLE user_settings
    -- Minutes, Monday first, 7 values. Never set together with screen_time_limit_minutes: the
    -- service refuses that. Both null is off.
    ADD COLUMN screen_time_limits_by_day         INTEGER[],
    -- bcrypt; no endpoint returns it. Set exactly while a limit is on, so turning the limit off and
    -- on again is how a forgotten passcode is replaced.
    ADD COLUMN screen_time_passcode_hash         VARCHAR(60),
    -- Wrong passcodes since the last right one or the last lock; the fifth locks checking for 15
    -- minutes. Counted here because user-service has no Redis.
    ADD COLUMN screen_time_passcode_failures     INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN screen_time_passcode_locked_until TIMESTAMPTZ;

-- Limits saved before there were passcodes were never enforced, and without a passcode the new
-- limit screen could never be dismissed. Turned off rather than kept.
UPDATE user_settings SET screen_time_limit_minutes = NULL WHERE screen_time_limit_minutes IS NOT NULL;
