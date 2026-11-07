-- Saved equipment-tab coverage (STORY-CHAR-005). Reference/presentation data only: nothing here is
-- owned stock and none of it is read by inventory or crafting calculations. Canonical item display
-- metadata (name, type, rarity, icon) stays in the shared items table and is joined at read time.

-- One row per account: the CHARACTERS generation the last equipment publication enumerated. Written
-- in the same transaction as the tab rows and the CHARACTER_EQUIPMENT_TABS source marker, so the
-- three commit or roll back together. Compatibility = this value equals the current CHARACTERS
-- generation.
CREATE TABLE character_equipment_coverage (
    account_id        UUID   NOT NULL REFERENCES account_registry (account_id),
    parent_generation BIGINT NOT NULL,
    CONSTRAINT character_equipment_coverage_pkey PRIMARY KEY (account_id),
    CONSTRAINT character_equipment_coverage_generation_check CHECK (parent_generation >= 1)
);

CREATE TABLE character_equipment_tabs (
    account_id   UUID        NOT NULL,
    character_id BIGINT      NOT NULL,
    tab_number   INTEGER     NOT NULL,
    -- Zero-based position in the upstream response; display order (tab numbers need not be contiguous).
    tab_order    INTEGER     NOT NULL,
    name         TEXT,
    is_active    BOOLEAN     NOT NULL,
    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT character_equipment_tabs_pkey PRIMARY KEY (account_id, character_id, tab_number),
    CONSTRAINT character_equipment_tabs_number_check CHECK (tab_number > 0),
    CONSTRAINT fk_character_equipment_tabs_parent
        FOREIGN KEY (account_id, character_id)
        REFERENCES characters (account_id, character_id) ON DELETE CASCADE
);

-- At most one active tab per character.
CREATE UNIQUE INDEX uq_character_equipment_tabs_active
    ON character_equipment_tabs (account_id, character_id) WHERE is_active;

CREATE TABLE character_equipment_tab_entries (
    account_id       UUID    NOT NULL,
    character_id     BIGINT  NOT NULL,
    tab_number       INTEGER NOT NULL,
    entry_order      INTEGER NOT NULL,
    slot             TEXT,
    item_id          INTEGER NOT NULL,
    location         TEXT,
    upgrade_ids      INTEGER[] NOT NULL DEFAULT '{}',
    infusion_ids     INTEGER[] NOT NULL DEFAULT '{}',
    stat_id          INTEGER,
    stat_attributes  JSONB,
    binding          TEXT,
    bound_to         TEXT,
    CONSTRAINT character_equipment_tab_entries_pkey
        PRIMARY KEY (account_id, character_id, tab_number, entry_order),
    CONSTRAINT character_equipment_tab_entries_item_check CHECK (item_id > 0),
    CONSTRAINT fk_character_equipment_entries_tab
        FOREIGN KEY (account_id, character_id, tab_number)
        REFERENCES character_equipment_tabs (account_id, character_id, tab_number) ON DELETE CASCADE
);
