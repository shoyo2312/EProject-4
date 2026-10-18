-- "Download your data": one row per request. PROCESSING until DataExportWorker has written the
-- archive to object storage, then READY until expires_at; CANCELED, FAILED and EXPIRED are final.
-- object_key stays set on a final row until DataExportCleanup has removed the file, which is how
-- the cleanup finds the files still to delete.
--
-- claimed_at is the worker's lease: a row claimed longer ago than the lease belongs to a worker
-- that died or a build that failed, and is picked up again until attempts runs out.
CREATE TABLE data_export_requests (
    id          BIGINT       PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    format      VARCHAR(8)   NOT NULL,
    categories  TEXT[]       NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    claimed_at  TIMESTAMPTZ,
    attempts    INT          NOT NULL DEFAULT 0,
    object_key  VARCHAR(255),
    size_bytes  BIGINT,
    ready_at    TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    deleted_at  TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0
);

-- The owner's list, newest first.
CREATE INDEX idx_data_export_requests_user_created
    ON data_export_requests (user_id, created_at DESC) WHERE deleted_at IS NULL;
-- One request in progress per account: the service checks first, this settles two requests racing.
CREATE UNIQUE INDEX uq_data_export_requests_user_processing
    ON data_export_requests (user_id) WHERE status = 'PROCESSING' AND deleted_at IS NULL;
-- The worker's queue, oldest first.
CREATE INDEX idx_data_export_requests_processing
    ON data_export_requests (created_at) WHERE status = 'PROCESSING' AND deleted_at IS NULL;
