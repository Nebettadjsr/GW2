-- Account wallet balances (currencies such as Research Notes, currency 61). Account-scoped like every other
-- account source; the currency id is its own namespace and is never an item id.
CREATE TABLE account_wallet (
    account_id  UUID        NOT NULL REFERENCES account_registry (account_id) ON DELETE CASCADE,
    currency_id INTEGER     NOT NULL,
    value       BIGINT      NOT NULL CHECK (value >= 0),
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_wallet_pkey PRIMARY KEY (account_id, currency_id)
);
