-- Fields the admin console shows on a user row but had no home for.
--
-- last_login_at: advanced every time a token pair is issued for the account — a fresh sign-in
--   or a refresh. Enough to tell a dormant account from a live one; not a precise login clock.
-- banned_at / ban_reason: written when auth-service consumes a UserBannedEvent, so the console
--   can say when and why without a second call into admin-service's audit log. Cleared on unban.
ALTER TABLE users
    ADD COLUMN last_login_at TIMESTAMPTZ,
    ADD COLUMN banned_at     TIMESTAMPTZ,
    ADD COLUMN ban_reason    TEXT;
