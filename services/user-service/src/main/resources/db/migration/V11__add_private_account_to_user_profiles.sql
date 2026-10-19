-- Only approved followers see a private account's videos, lists and reposts. On the profile rather
-- than on user_settings (V10) because every viewer needs it — the profile header, the Follow
-- button — and it rides the profile reads that already happen. Switched through the settings API
-- all the same, where the switch lives.
--
-- DEFAULT FALSE leaves every existing account public, which is what they all are: nothing could
-- make an account private before this column existed.
ALTER TABLE user_profiles ADD COLUMN private_account BOOLEAN NOT NULL DEFAULT FALSE;
