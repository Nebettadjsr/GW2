-- Global display metadata of wallet currencies (name and image source), fetched from the public currencies catalog.
-- The currency id is its own namespace and is never an item id; the icon is served through the same-origin icon cache.
CREATE TABLE wallet_currencies (
    currency_id INTEGER     NOT NULL,
    name        TEXT        NOT NULL,
    icon_url    TEXT,
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT wallet_currencies_pkey PRIMARY KEY (currency_id)
);
