-- State versions include soft-deleted history, which the existing live-only indexes cannot serve.
CREATE INDEX idx_user_blocks_pair_history ON user_blocks (blocker_id, blocked_id);
CREATE INDEX idx_user_blocks_reverse_pair_history ON user_blocks (blocked_id, blocker_id);
