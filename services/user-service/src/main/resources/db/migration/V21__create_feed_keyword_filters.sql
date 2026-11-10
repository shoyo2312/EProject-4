-- Settings → Content preferences → Filter keywords: words or hashtags whose videos the account does
-- not want in For You, Following or Friends. Enforced by video-service at read time, from the lists
-- the internal settings contract hands it. See docs/user-settings.md.
CREATE TABLE feed_keyword_filters (
    id         BIGINT       PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    -- As typed (sanitized): one word or one hashtag, at most 200 characters.
    keyword    VARCHAR(200) NOT NULL,
    for_you    BOOLEAN      NOT NULL,
    following  BOOLEAN      NOT NULL,
    friends    BOOLEAN      NOT NULL,

    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    deleted_at TIMESTAMPTZ,
    version    BIGINT       NOT NULL DEFAULT 0
);

-- "Spelling isn't case-sensitive": one keyword per account however it is capitalized. Also what the
-- per-account list and the duplicate check read, both by lower(keyword).
CREATE UNIQUE INDEX uq_feed_keyword_filters_user_keyword
    ON feed_keyword_filters (user_id, lower(keyword)) WHERE deleted_at IS NULL;
