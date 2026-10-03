-- Per-source successful account synchronization timestamps.
-- Empty responses are represented by a marker row, so absence never means successful empty data.
CREATE TABLE IF NOT EXISTS account_sync_state (
    source TEXT PRIMARY KEY,
    fetched_at TIMESTAMPTZ NOT NULL
);
