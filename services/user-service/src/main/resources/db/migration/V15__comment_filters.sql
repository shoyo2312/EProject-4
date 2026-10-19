-- A creator's own comment filters, enforced by interaction-service on every comment posted under
-- their videos (relayed through video-service's /policy). See docs/user-settings.md.
--
-- The DEFAULT clauses mirror UserSettings.defaultsFor(), like V10's: an account that never opened
-- the setting is filtered for spam and offence, holds nothing else back, and filters no keywords.
ALTER TABLE user_settings
    ADD COLUMN filter_spam_offensive BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN filter_all_comments   BOOLEAN NOT NULL DEFAULT FALSE,
    -- As typed (sanitized), at most 50; matched by whole syllables, see moderation-lib KeywordFilter.
    ADD COLUMN comment_keywords      TEXT[]  NOT NULL DEFAULT '{}';
