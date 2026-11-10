-- One pending report per reporter per target, not one report ever. Nothing soft-deletes a report:
-- resolve, dismiss and the stale sweep only change its status. So V3's index, partial on
-- deleted_at alone, let a closed report block its reporter for good. The repeat came back as the
-- old closed report, and a harassment victim's report on the next round never reached the queue.
-- The queue counts pending reports only (V5), so this still stops one user stacking a target.
--
-- Same name, because submitReport matches it to turn a concurrent duplicate submit into a 409.
-- Every row the old index allowed, this one allows, so nothing needs deduping first.
DROP INDEX idx_reports_one_per_reporter_target;

CREATE UNIQUE INDEX idx_reports_one_per_reporter_target
    ON reports (reporter_id, target_type, target_id)
    WHERE deleted_at IS NULL AND status = 'PENDING';
