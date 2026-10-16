-- Terms admins add to (HOLD, REJECT) or take out of (EXEMPT) the text moderation list every service
-- ships with — see docs/moderation.md. interaction-service and video-service poll the live rows
-- through /api/v1/admin/internal/blocklist and rebuild their matcher when the revision moves.
CREATE TABLE moderation_terms (
    id          BIGINT        PRIMARY KEY,
    -- As the admin typed it, sanitized to one line.
    term        VARCHAR(100)  NOT NULL,
    -- The term as the matcher keys it (moderation-lib Blocklist.keyOf): its syllables, diacritics
    -- kept when it was written with them. Two spellings with the same key are one term to every
    -- service, so only one of them may be live.
    term_key    VARCHAR(200)  NOT NULL,
    -- NULL for EXEMPT, which flags nothing.
    category    VARCHAR(20),
    action      VARCHAR(10)   NOT NULL,
    note        VARCHAR(500),
    created_by  BIGINT        NOT NULL,
    deleted_by  BIGINT,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    deleted_at  TIMESTAMPTZ,
    version     BIGINT        NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_moderation_terms_term_key ON moderation_terms (term_key) WHERE deleted_at IS NULL;
CREATE INDEX idx_moderation_terms_live ON moderation_terms (created_at DESC) WHERE deleted_at IS NULL;

-- The list's revision: one row, moved by every add and every delete in the same transaction as the
-- change. The row lock is the point. Writers queue on it, so revisions commit in the order they are
-- taken; with a sequence instead, a change committing after a higher revision was already read
-- would never be fetched — the pollers compare revisions, and the maximum would not have moved.
CREATE TABLE moderation_terms_revision (
    id        SMALLINT  PRIMARY KEY CHECK (id = 1),
    revision  BIGINT    NOT NULL
);

INSERT INTO moderation_terms_revision (id, revision) VALUES (1, 0);
