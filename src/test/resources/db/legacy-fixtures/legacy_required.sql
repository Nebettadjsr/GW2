-- The required half of the supported pre-migration manual schema
-- (infra.schema.LegacySchemaFingerprint.REQUIRED_OBJECTS).
--
-- Reproduced from a read-only catalog inspection of the actual deployed manual schema: column order,
-- underlying types, nullability and every constraint definition match it. Constraint names are not
-- part of the fingerprint, but the ones below are the names the real database carries.

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

CREATE TABLE account_bank (
    slot        INTEGER PRIMARY KEY,
    item_id     INTEGER,
    count       INTEGER,
    binding     TEXT,
    bound_to    TEXT,
    charges     INTEGER,
    stats_id    INTEGER,
    stats_attrs JSONB,
    fetched_at  TIMESTAMPTZ
);

CREATE TABLE account_materials (
    item_id    INTEGER PRIMARY KEY,
    category   INTEGER,
    count      INTEGER,
    binding    TEXT,
    fetched_at TIMESTAMPTZ
);

CREATE TABLE account_recipes (
    recipe_id  INTEGER PRIMARY KEY,
    fetched_at TIMESTAMPTZ
);

CREATE TABLE characters (
    character_id  BIGSERIAL PRIMARY KEY,
    name          TEXT NOT NULL UNIQUE,
    profession    TEXT,
    race          TEXT,
    gender        TEXT,
    level         INTEGER,
    created_at_gw TIMESTAMPTZ,
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE character_crafting (
    character_id BIGINT  NOT NULL REFERENCES characters (character_id) ON DELETE CASCADE,
    discipline   TEXT    NOT NULL,
    rating       INTEGER NOT NULL DEFAULT 0,
    is_active    BOOLEAN NOT NULL DEFAULT FALSE,
    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (character_id, discipline)
);

CREATE TABLE character_recipes (
    character_id BIGINT  NOT NULL REFERENCES characters (character_id) ON DELETE CASCADE,
    recipe_id    INTEGER NOT NULL,
    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (character_id, recipe_id)
);

CREATE TABLE character_items (
    character_id   BIGINT  NOT NULL REFERENCES characters (character_id) ON DELETE CASCADE,
    location       TEXT    NOT NULL,
    bag_index      INTEGER,
    slot_index     INTEGER,
    equipment_slot TEXT,
    item_id        INTEGER NOT NULL,
    count          INTEGER NOT NULL DEFAULT 1,
    binding        TEXT,
    bound_to       TEXT,
    fetched_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_character_items_slot
        UNIQUE (character_id, location, bag_index, slot_index, equipment_slot)
);

CREATE INDEX idx_character_crafting_character ON character_crafting (character_id);
CREATE INDEX idx_character_items_character ON character_items (character_id);
CREATE INDEX idx_character_items_item ON character_items (item_id);
CREATE INDEX idx_character_recipes_character ON character_recipes (character_id);
CREATE INDEX idx_character_recipes_recipe_id ON character_recipes (recipe_id);
