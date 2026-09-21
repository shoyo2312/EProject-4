-- The queue an admin actually works: one row per target, not one per report. Fifty reports
-- against one video are one decision, and the grouped query behind it scans every PENDING row
-- every time it is opened. idx_reports_status covers the filter but not the grouping, so the
-- planner sorts the whole filtered set on each page.
CREATE INDEX idx_reports_pending_target
    ON reports (target_type, target_id)
    WHERE deleted_at IS NULL AND status = 'PENDING';

-- How much one reported scenario weighs when the queue is ordered. A table rather than a CASE
-- in the query or an enum in Java, because these are the numbers most likely to be wrong on the
-- first try: a moderation lead retunes them with an UPDATE, without a deploy.
--
-- `reason` is the client's scenario label, stored verbatim (see tiktok-cloned
-- lib/api/reports.ts — the labels are declared stable for exactly this reason). A report whose
-- reason matches nothing here still gets ranked, at DEFAULT_REASON_WEIGHT in ReportRepository:
-- an unrecognised label must not sort to the bottom and disappear.
CREATE TABLE report_reason_weights (
    reason      VARCHAR(1000) PRIMARY KEY,
    weight      INT           NOT NULL CHECK (weight BETWEEN 1 AND 10),
    -- Aged-out reports are auto-dismissed below this line and never above it, so the sweep can
    -- never quietly close a self-harm report that no one got to.
    auto_expire BOOLEAN       NOT NULL DEFAULT TRUE
);

INSERT INTO report_reason_weights (reason, weight, auto_expire) VALUES
    ('Suicide and self-harm',                        10, FALSE),
    ('Violence, abuse, and criminal exploitation',     9, FALSE),
    ('Nudity and sexual content',                      8, FALSE),
    ('Sharing personal information',                   7, FALSE),
    ('Hate and harassment',                            6, TRUE),
    ('Shocking and graphic content',                   5, TRUE),
    ('Dangerous activities and challenges',            4, TRUE),
    ('Illegal activities and regulated goods',         4, TRUE),
    ('Regulated goods and activities',                 4, TRUE),
    ('Frauds and scams',                               3, TRUE),
    ('Deceptive behavior and spam',                    2, TRUE),
    ('Misinformation',                                 2, TRUE),
    ('Intellectual property violation',                1, TRUE),
    ('Counterfeits and intellectual property',         1, TRUE);
