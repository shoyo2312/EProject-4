-- The approximate place of ip_address ("Ho Chi Minh City, Vietnam", sometimes the country alone),
-- resolved from the GeoIP file when the row was written (GeoIpLocator). Stored rather than looked
-- up on read, so the admin and the account's owner see the same value and the history does not
-- move when the file is refreshed. Null for every row from before this migration, and wherever the
-- address resolves to nothing — never a guess.
ALTER TABLE auth_security_events ADD COLUMN location VARCHAR(128);
