-- STORY-INFRA-004 / TARGET_ARCHITECTURE.md 7.2-7.3.
--
-- The global (shared) half of the schema: Guild Wars 2 item and recipe metadata, the Trading Post
-- cache and the ordered material catalog. Nothing here is account-owned, so legacy adoption leaves
-- every one of these tables and all of its rows in place.
--
-- This version is deliberately identical to the legacy manual schema's global objects, because it is
-- the version an adopted legacy database is baselined at (infra.schema.LegacySchemaFingerprint).
-- An empty database executes this file; an adopted database already contains exactly these objects.
-- Both then continue with V2, which is what makes the two converge on one live schema.
--
-- Applied migrations are immutable: correct a mistake here with a new version, never by editing
-- this file (editing it makes every existing database fail checksum validation at startup).

CREATE TABLE items (
    item_id      INTEGER PRIMARY KEY,
    name         TEXT,
    type         TEXT,
    rarity       TEXT,
    level        INTEGER,
    vendor_value INTEGER,
    fetched_at   TIMESTAMPTZ,
    icon_url     TEXT,
    icon_path    TEXT
);

CREATE TABLE recipes (
    recipe_id         INTEGER PRIMARY KEY,
    type              TEXT,
    output_item_id    INTEGER,
    output_item_count INTEGER,
    time_to_craft_ms  INTEGER,
    min_rating        INTEGER,
    chat_link         TEXT,
    disciplines       TEXT[],
    flags             TEXT[],
    guild_ingredients JSONB,
    fetched_at        TIMESTAMPTZ
);

CREATE TABLE recipe_ingredients (
    recipe_id INTEGER NOT NULL,
    item_id   INTEGER NOT NULL,
    count     INTEGER,
    CONSTRAINT recipe_ingredients_pkey PRIMARY KEY (recipe_id, item_id),
    CONSTRAINT fk_recipe_ingredients_recipe FOREIGN KEY (recipe_id) REFERENCES recipes (recipe_id) ON DELETE CASCADE,
    CONSTRAINT fk_recipe_ingredients_item FOREIGN KEY (item_id) REFERENCES items (item_id)
);

CREATE TABLE tp_tradeable_items (
    item_id    INTEGER PRIMARY KEY,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tp_prices (
    item_id         INTEGER PRIMARY KEY,
    buy_quantity    BIGINT,
    buy_unit_price  INTEGER,
    sell_quantity   BIGINT,
    sell_unit_price INTEGER,
    fetched_at      TIMESTAMPTZ
);

CREATE TABLE material_categories (
    category_id   INTEGER PRIMARY KEY,
    name          TEXT    NOT NULL,
    display_order INTEGER NOT NULL
);

CREATE TABLE material_category_items (
    category_id INTEGER NOT NULL,
    position    INTEGER NOT NULL,
    item_id     INTEGER NOT NULL,
    CONSTRAINT material_category_items_pkey PRIMARY KEY (category_id, position),
    CONSTRAINT material_category_items_category_id_fkey
        FOREIGN KEY (category_id) REFERENCES material_categories (category_id) ON DELETE CASCADE
);
