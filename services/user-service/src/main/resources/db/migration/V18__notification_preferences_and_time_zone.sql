-- The switches behind Settings → Push notifications. Interactions (push_*) stops the phone push and
-- the desktop notification for that type; In-app (in_app_*) stops the banner. The inbox entry is
-- written either way. See docs/user-settings.md.
--
-- Every switch defaults on. The DEFAULT clauses mirror UserSettings.defaultsFor(), like V10's and
-- V15's — keep the two in step.
ALTER TABLE user_settings
    ADD COLUMN push_likes           BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN push_comments        BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN push_new_followers   BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN in_app_likes         BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN in_app_comments      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN in_app_new_followers BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN in_app_reposts       BOOLEAN NOT NULL DEFAULT TRUE,
    -- The browser's IANA zone, written only by the screen-time heartbeat's upsert. Null until the
    -- web has sent one, and until then sleep hours mute no push.
    ADD COLUMN time_zone            VARCHAR(64);

-- Sleep hours now always end 8 hours after they start, and the server derives the end. Rows saved
-- by the old two-field modal get the same rule, so the window notification-service mutes is the
-- one the settings page shows. TIME + INTERVAL wraps past midnight: 23:30 becomes 07:30.
UPDATE user_settings
SET sleep_hours_end = sleep_hours_start + INTERVAL '8 hours'
WHERE sleep_hours_start IS NOT NULL;
