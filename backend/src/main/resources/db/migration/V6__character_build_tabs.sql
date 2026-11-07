-- Saved build-tab coverage (STORY-CHAR-007). Reference/presentation data only: nothing here is owned
-- stock. Specialization, trait, skill, pet and legend values are stored as the raw upstream
-- identifiers; no names or icons are copied here (metadata is a downstream story).

-- One row per account: the CHARACTERS generation the last build-tab publication enumerated. Written in
-- the same transaction as the tab rows and the CHARACTER_BUILD_TABS source marker.
CREATE TABLE character_build_coverage (
    account_id        UUID   NOT NULL REFERENCES account_registry (account_id),
    parent_generation BIGINT NOT NULL,
    CONSTRAINT character_build_coverage_pkey PRIMARY KEY (account_id),
    CONSTRAINT character_build_coverage_generation_check CHECK (parent_generation >= 1)
);

-- Skill columns are NULL when the character chose nothing for that slot. *_legends / *_pets are NULL
-- when the upstream build carried none (non-Revenant / non-Ranger); otherwise a two-position array whose
-- elements may be NULL (an empty position). Utilities are exactly three positions.
CREATE TABLE character_build_tabs (
    account_id       UUID        NOT NULL,
    character_id     BIGINT      NOT NULL,
    tab_number       INTEGER     NOT NULL,
    name             TEXT,
    profession       TEXT,
    is_active        BOOLEAN     NOT NULL,
    land_heal        INTEGER,
    land_elite       INTEGER,
    land_utilities   INTEGER[]   NOT NULL,
    aquatic_heal     INTEGER,
    aquatic_elite    INTEGER,
    aquatic_utilities INTEGER[]  NOT NULL,
    land_legends     TEXT[],
    aquatic_legends  TEXT[],
    land_pets        INTEGER[],
    aquatic_pets     INTEGER[],
    fetched_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT character_build_tabs_pkey PRIMARY KEY (account_id, character_id, tab_number),
    CONSTRAINT character_build_tabs_number_check CHECK (tab_number > 0),
    CONSTRAINT character_build_tabs_utilities_check
        CHECK (cardinality(land_utilities) = 3 AND cardinality(aquatic_utilities) = 3),
    CONSTRAINT character_build_tabs_legends_check
        CHECK ((land_legends IS NULL OR cardinality(land_legends) = 2)
           AND (aquatic_legends IS NULL OR cardinality(aquatic_legends) = 2)),
    CONSTRAINT character_build_tabs_pets_check
        CHECK ((land_pets IS NULL OR cardinality(land_pets) = 2)
           AND (aquatic_pets IS NULL OR cardinality(aquatic_pets) = 2)),
    CONSTRAINT fk_character_build_tabs_parent
        FOREIGN KEY (account_id, character_id)
        REFERENCES characters (account_id, character_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_character_build_tabs_active
    ON character_build_tabs (account_id, character_id) WHERE is_active;

-- Exactly three ordered specialization positions per tab; a position may be unselected (NULL id),
-- and every position carries three nullable trait positions (all NULL when unselected), matching the
-- documented {"id":null,"traits":[null,null,null]} object.
CREATE TABLE character_build_tab_specializations (
    account_id        UUID    NOT NULL,
    character_id      BIGINT  NOT NULL,
    tab_number        INTEGER NOT NULL,
    position          INTEGER NOT NULL,
    specialization_id INTEGER,
    traits            INTEGER[]  NOT NULL,
    CONSTRAINT character_build_tab_specializations_pkey
        PRIMARY KEY (account_id, character_id, tab_number, position),
    CONSTRAINT character_build_tab_specializations_position_check CHECK (position BETWEEN 1 AND 3),
    CONSTRAINT character_build_tab_specializations_shape_check CHECK (
        cardinality(traits) = 3 AND (
            (specialization_id IS NULL AND cardinality(array_remove(traits, NULL)) = 0)
            OR (specialization_id IS NOT NULL AND specialization_id > 0))),
    CONSTRAINT fk_character_build_specializations_tab
        FOREIGN KEY (account_id, character_id, tab_number)
        REFERENCES character_build_tabs (account_id, character_id, tab_number) ON DELETE CASCADE
);
