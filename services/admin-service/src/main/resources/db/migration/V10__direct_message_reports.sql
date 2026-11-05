-- A report filed from a direct-message conversation: still a USER report, so Ban, Warn and Dismiss,
-- the queue grouping, standing enforcement and the stale sweep all apply unchanged, but with the
-- messages the reporter picked attached as evidence.
--
-- Existing rows are profile reports: the default says so, and they carry no evidence.
ALTER TABLE reports
    ADD COLUMN source          VARCHAR(30) NOT NULL DEFAULT 'PROFILE',
    ADD COLUMN conversation_id VARCHAR(64),
    -- The client's idempotency key. A retry of the same submit finds its own report by this and
    -- gets it back, instead of a second report, a second event and a second set of evidence.
    ADD COLUMN request_id      VARCHAR(64),
    ADD COLUMN evidence_count  INT         NOT NULL DEFAULT 0;

-- One pending report per reporter per target (V9) stays the rule for profile reports. A direct-
-- message report is a case about what was said, and a second one with other messages must not be
-- answered with the first: that dropped the new evidence. The retry guard is request_id instead.
DROP INDEX idx_reports_one_per_reporter_target;

CREATE UNIQUE INDEX idx_reports_one_per_reporter_target
    ON reports (reporter_id, target_type, target_id)
    WHERE deleted_at IS NULL AND status = 'PENDING' AND source = 'PROFILE';

CREATE UNIQUE INDEX idx_reports_reporter_request
    ON reports (reporter_id, request_id)
    WHERE request_id IS NOT NULL;

-- What the reporter could see when they filed, captured by chat-service at that moment. A copy:
-- the message may be recalled tomorrow and the admin must still be able to read what was reported.
-- Media is an id only, never a URL that would expire or a link that would expose a private upload.
CREATE TABLE report_evidence (
    id              BIGINT PRIMARY KEY,
    report_id       BIGINT       NOT NULL REFERENCES reports (id),
    conversation_id VARCHAR(64)  NOT NULL,
    message_id      VARCHAR(64)  NOT NULL,
    sender_id       BIGINT       NOT NULL,
    message_type    VARCHAR(30)  NOT NULL,
    sent_at         TIMESTAMPTZ  NOT NULL,
    captured_at     TIMESTAMPTZ  NOT NULL,
    content         TEXT,
    sticker_id      VARCHAR(100),
    video_id        VARCHAR(100),
    story_id        VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (report_id, message_id)
);

CREATE INDEX idx_report_evidence_report ON report_evidence (report_id, sent_at);

-- Reason labels the direct-message flow adds. Same table, same meaning as V5: the sensitive ones do
-- not age out, and a label missing here would be ranked at the default and could be swept.
INSERT INTO report_reason_weights (reason, weight, auto_expire) VALUES
    ('Sextortion and intimate image abuse',           10, FALSE),
    ('Minor safety',                                  10, FALSE),
    ('Disordered eating and unhealthy body image',     8, FALSE),
    ('Dangerous goods or services',                    4, TRUE),
    ('Intellectual property infringement',             1, TRUE),
    ('Advertisement does not match the item',          1, TRUE),
    ('Other',                                          2, TRUE)
ON CONFLICT (reason) DO NOTHING;
