-- Existing duplicates first, or the unique index below cannot be built and this migration fails
-- on any database that has been taking reports. The oldest row of each group is the one kept: it
-- is the report the console has been showing and the one an admin may already have started on.
-- The rest are soft-deleted, not removed — a report is a domain record, and §6 allows no hard
-- delete here. Snowflake ids are time-ordered, so MIN(id) is the earliest.
UPDATE reports r
SET deleted_at = NOW()
WHERE deleted_at IS NULL
  AND id <> (
      SELECT MIN(keep.id) FROM reports keep
      WHERE keep.deleted_at IS NULL
        AND keep.reporter_id = r.reporter_id
        AND keep.target_type = r.target_type
        AND keep.target_id = r.target_id);

-- One standing report per reporter per target. countReports is what the console ranks a target
-- by, so without this a single user can file the same report a hundred times and push anything
-- to the top of the queue.
--
-- Partial on deleted_at IS NULL: a dismissed-and-soft-deleted report must not block the same
-- user from reporting that target again later over something new.
CREATE UNIQUE INDEX idx_reports_one_per_reporter_target
    ON reports (reporter_id, target_type, target_id)
    WHERE deleted_at IS NULL;
