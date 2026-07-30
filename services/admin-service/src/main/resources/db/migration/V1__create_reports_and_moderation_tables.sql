CREATE TABLE reports (
    id              BIGINT PRIMARY KEY,
    reporter_id     BIGINT        NOT NULL,
    target_type     VARCHAR(20)   NOT NULL,
    target_id       VARCHAR(100)  NOT NULL,
    reason          VARCHAR(1000) NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    resolved_by     BIGINT,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_reports_status ON reports (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_reports_target ON reports (target_type, target_id) WHERE deleted_at IS NULL;

CREATE TABLE moderation_actions (
    id              BIGINT PRIMARY KEY,
    admin_id        BIGINT        NOT NULL,
    action_type     VARCHAR(30)   NOT NULL,
    target_type     VARCHAR(20)   NOT NULL,
    target_id       VARCHAR(100)  NOT NULL,
    reason          VARCHAR(1000) NOT NULL,
    report_id       BIGINT,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_moderation_actions_target ON moderation_actions (target_type, target_id);
CREATE INDEX idx_moderation_actions_created_at ON moderation_actions (created_at);
