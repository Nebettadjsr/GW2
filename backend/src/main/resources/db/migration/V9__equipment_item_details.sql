-- Shared, globally deduplicated slim public details of the items, upgrades and infusions referenced by
-- saved equipment tabs (STORY-CHAR-014), plus the /v2/itemstats rows needed to resolve selected stats.
-- Public GW2 data only: no account column. Disposable cache (UD-016); missing rows are re-fetched.
CREATE TABLE equipment_item_details (
    item_id    INTEGER     NOT NULL,
    details    JSONB       NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT equipment_item_details_pkey PRIMARY KEY (item_id)
);

CREATE TABLE equipment_item_stats (
    stat_id    INTEGER     NOT NULL,
    attributes JSONB       NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT equipment_item_stats_pkey PRIMARY KEY (stat_id)
);
