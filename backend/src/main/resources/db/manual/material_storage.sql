-- Apply once to databases created before ordered material catalog support.
CREATE TABLE IF NOT EXISTS material_categories (
    category_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    display_order INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS material_category_items (
    category_id INTEGER NOT NULL REFERENCES material_categories(category_id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    item_id INTEGER NOT NULL,
    PRIMARY KEY (category_id, position)
);

CREATE TABLE IF NOT EXISTS account_materials_sync (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    fetched_at TIMESTAMPTZ NOT NULL
);

-- Preserve a known non-empty pre-migration snapshot. An empty legacy table has no proof of sync,
-- so it stays unavailable until the next successful account-material refresh.
INSERT INTO account_materials_sync (id, fetched_at)
SELECT 1, COALESCE(MAX(fetched_at), now()) FROM account_materials HAVING COUNT(*) > 0
ON CONFLICT (id) DO NOTHING;
