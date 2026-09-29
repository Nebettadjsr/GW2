-- Account Luck schema. Backend startup and account sync apply this idempotently.
-- The unqualified name follows the connection's active schema (public in normal use).
CREATE TABLE IF NOT EXISTS account_luck (
  account_id    TEXT PRIMARY KEY,
  consumed_luck BIGINT NOT NULL CHECK (consumed_luck >= 0),
  fetched_at    TIMESTAMPTZ NOT NULL
);
