-- Optional variant from db/manual/account_luck.sql: the one identity-aware legacy account table.
CREATE TABLE account_luck (
    account_id    TEXT PRIMARY KEY,
    consumed_luck BIGINT NOT NULL CHECK (consumed_luck >= 0),
    fetched_at    TIMESTAMPTZ NOT NULL
);
