-- Shared, globally deduplicated display metadata for the specializations, traits, skills, pets and
-- legends referenced by saved builds (STORY-CHAR-009). Public GW2 reference data only: no account
-- column, no stock. PostgreSQL is a disposable cache (UD-016); rows are re-fetched when missing.
-- Native identifiers are never copied into character_build_* rows, so metadata repair never touches
-- the account publication rows or generations.

CREATE TABLE build_specializations (
    specialization_id INTEGER     NOT NULL,
    name              TEXT        NOT NULL,
    profession        TEXT,
    elite             BOOLEAN     NOT NULL,
    icon_url          TEXT,
    minor_traits      INTEGER[]   NOT NULL,
    major_traits      INTEGER[]   NOT NULL,
    fetched_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT build_specializations_pkey PRIMARY KEY (specialization_id)
);

-- tier (1..3 = adept, master, grandmaster) and slot (Major/Minor) are the upstream values, NULL when
-- upstream did not state them.
CREATE TABLE build_traits (
    trait_id          INTEGER     NOT NULL,
    name              TEXT        NOT NULL,
    icon_url          TEXT,
    specialization_id INTEGER,
    tier              INTEGER,
    slot              TEXT,
    fetched_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT build_traits_pkey PRIMARY KEY (trait_id)
);

CREATE TABLE build_skills (
    skill_id   INTEGER     NOT NULL,
    name       TEXT        NOT NULL,
    icon_url   TEXT,
    fetched_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT build_skills_pkey PRIMARY KEY (skill_id)
);

CREATE TABLE build_pets (
    pet_id     INTEGER     NOT NULL,
    name       TEXT        NOT NULL,
    icon_url   TEXT,
    fetched_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT build_pets_pkey PRIMARY KEY (pet_id)
);

-- A legend has no native name or icon upstream: only the related swap skill is retained.
CREATE TABLE build_legends (
    legend_id     TEXT        NOT NULL,
    swap_skill_id INTEGER     NOT NULL,
    fetched_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT build_legends_pkey PRIMARY KEY (legend_id)
);
