-- STORY-INFRA-004 / TARGET_ARCHITECTURE.md 7.1-7.2.
--
-- The account-owned half of the schema. Every table here is keyed by the verified /v2/account GUID,
-- which is the registry's own primary key, so replacing an API key for the same account reuses this
-- dataset. Account-owned uniqueness always includes that GUID, and character children reach their
-- parent through a composite (account_id, character_id) foreign key, so no child can ever name a
-- character owned by another account.
--
-- Deliberately absent: API keys, key hashes, session identifiers/verifiers, passwords and any
-- application-user identity. Validated key possession is the only application identity
-- (TARGET_ARCHITECTURE.md 8.1), it is held transiently in memory, and nothing here is a
-- credential store.
--
-- These tables reuse the legacy names because they replace those tables outright: the legacy
-- unscoped cache is dropped by the startup reset (UD-016, section 7.4), never migrated into them.
-- An unscoped legacy statement is therefore refused at the runtime database access boundary by
-- repo.ManagedSchema rather than by a missing relation.

CREATE TABLE account_registry (
    account_id              UUID PRIMARY KEY,
    display_name            TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at            TIMESTAMPTZ,
    last_successful_sync_at TIMESTAMPTZ
);

COMMENT ON TABLE account_registry IS
    'Account registry keyed by the verified /v2/account GUID. Registration of the same GUID is idempotent. Holds no credential, credential hash, session or application-user identity.';
COMMENT ON COLUMN account_registry.display_name IS
    'Optional descriptive metadata only; never an identity or ownership proof.';
COMMENT ON COLUMN account_registry.created_at IS
    'When this registry row was created. Not a claim about when the Guild Wars 2 account was created.';
COMMENT ON COLUMN account_registry.last_seen_at IS
    'Last successfully authorized account use, or NULL when unknown. A reset leaves it NULL rather than fabricating a historical visit.';
COMMENT ON COLUMN account_registry.last_successful_sync_at IS
    'Account-wide marker meaning only that some source succeeded, or NULL when unknown. Per-source completion in account_source_state remains the freshness authority.';

-- One durable source-state relation for every account source (bank, materials, account recipes,
-- characters, Luck). No row means the source is missing; a row means that source published
-- successfully at least once, including a successful empty response. An empty table is therefore
-- never a successful empty synchronization.
CREATE TABLE account_source_state (
    account_id   UUID        NOT NULL REFERENCES account_registry (account_id),
    source       TEXT        NOT NULL,
    generation   BIGINT      NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT account_source_state_pkey PRIMARY KEY (account_id, source),
    CONSTRAINT account_source_state_generation_check CHECK (generation > 0)
);

COMMENT ON COLUMN account_source_state.generation IS
    'Account/source-local generation, advanced only by a successful publication of that source. A timestamp is not a generation identifier, and generation 0 is not a completion.';

CREATE TABLE account_bank (
    account_id  UUID        NOT NULL REFERENCES account_registry (account_id),
    slot        INTEGER     NOT NULL,
    item_id     INTEGER,
    count       INTEGER,
    binding     TEXT,
    bound_to    TEXT,
    charges     INTEGER,
    stats_id    INTEGER,
    stats_attrs JSONB,
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_bank_pkey PRIMARY KEY (account_id, slot)
);

COMMENT ON COLUMN account_bank.bound_to IS
    'Bound-item name as returned upstream: domain input for bound-material rules, never authorization or identity evidence.';

CREATE TABLE account_materials (
    account_id UUID        NOT NULL REFERENCES account_registry (account_id),
    item_id    INTEGER     NOT NULL,
    category   INTEGER,
    count      INTEGER,
    binding    TEXT,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_materials_pkey PRIMARY KEY (account_id, item_id)
);

CREATE TABLE account_recipes (
    account_id UUID        NOT NULL REFERENCES account_registry (account_id),
    recipe_id  INTEGER     NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_recipes_pkey PRIMARY KEY (account_id, recipe_id)
);

COMMENT ON TABLE account_recipes IS
    'Account recipe knowledge. The shared crafting graph in recipes/recipe_ingredients stays global; account knowledge never partitions it.';

CREATE TABLE account_luck (
    account_id    UUID        NOT NULL REFERENCES account_registry (account_id),
    consumed_luck BIGINT      NOT NULL,
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_luck_pkey PRIMARY KEY (account_id),
    CONSTRAINT account_luck_consumed_luck_check CHECK (consumed_luck >= 0)
);

-- Character roster. The surrogate character_id is retained as the parent key children refer to, but
-- it is only unique together with the owning account, and a character name is unique within one
-- account rather than being a global ownership proof.
CREATE TABLE characters (
    account_id    UUID        NOT NULL REFERENCES account_registry (account_id),
    character_id  BIGINT      NOT NULL GENERATED BY DEFAULT AS IDENTITY,
    name          TEXT        NOT NULL,
    profession    TEXT,
    race          TEXT,
    gender        TEXT,
    level         INTEGER,
    created_at_gw TIMESTAMPTZ,
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT characters_pkey PRIMARY KEY (account_id, character_id),
    CONSTRAINT uq_characters_account_name UNIQUE (account_id, name)
);

CREATE TABLE character_crafting (
    account_id   UUID        NOT NULL,
    character_id BIGINT      NOT NULL,
    discipline   TEXT        NOT NULL,
    rating       INTEGER     NOT NULL DEFAULT 0,
    is_active    BOOLEAN     NOT NULL DEFAULT FALSE,
    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT character_crafting_pkey PRIMARY KEY (account_id, character_id, discipline),
    CONSTRAINT fk_character_crafting_parent
        FOREIGN KEY (account_id, character_id)
        REFERENCES characters (account_id, character_id) ON DELETE CASCADE
);

CREATE TABLE character_recipes (
    account_id   UUID        NOT NULL,
    character_id BIGINT      NOT NULL,
    recipe_id    INTEGER     NOT NULL,
    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT character_recipes_pkey PRIMARY KEY (account_id, character_id, recipe_id),
    CONSTRAINT fk_character_recipes_parent
        FOREIGN KEY (account_id, character_id)
        REFERENCES characters (account_id, character_id) ON DELETE CASCADE
);

-- Inventory and equipment. The legacy unique index over the same five location columns used
-- PostgreSQL's default NULLS DISTINCT, so two rows differing only by a NULL bag/slot/equipment part
-- never collided and a duplicate logical slot was representable. NULLS NOT DISTINCT states the
-- intended semantics explicitly instead of letting nullable columns decide them: one bag slot and
-- one equipment slot hold at most one row per character, whichever location components are NULL.
CREATE TABLE character_items (
    account_id     UUID        NOT NULL,
    character_id   BIGINT      NOT NULL,
    location       TEXT        NOT NULL,
    bag_index      INTEGER,
    slot_index     INTEGER,
    equipment_slot TEXT,
    item_id        INTEGER     NOT NULL,
    count          INTEGER     NOT NULL DEFAULT 1,
    binding        TEXT,
    bound_to       TEXT,
    fetched_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_character_items_parent
        FOREIGN KEY (account_id, character_id)
        REFERENCES characters (account_id, character_id) ON DELETE CASCADE,
    CONSTRAINT uq_character_items_slot
        UNIQUE NULLS NOT DISTINCT (account_id, character_id, location, bag_index, slot_index, equipment_slot)
);

-- Scoped access indexes lead with the account GUID. Lookups by the leading columns of a primary
-- key or unique constraint already have one, so only the other two access paths need an index.
CREATE INDEX idx_character_items_item ON character_items (account_id, item_id);
CREATE INDEX idx_character_recipes_recipe ON character_recipes (account_id, recipe_id);
