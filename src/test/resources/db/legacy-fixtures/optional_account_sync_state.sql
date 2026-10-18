-- Optional variant from db/manual/account_sync_state.sql: unscoped per-source completion markers.
CREATE TABLE account_sync_state (
    source     TEXT PRIMARY KEY,
    fetched_at TIMESTAMPTZ NOT NULL
);
