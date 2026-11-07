-- Character core summary (STORY-CHAR-012): played seconds and death count from the already-fetched
-- character document. NULL means unknown/not retained (a legacy cached row), never zero.
ALTER TABLE characters
    ADD COLUMN age_seconds BIGINT,
    ADD COLUMN deaths      BIGINT,
    ADD CONSTRAINT characters_age_seconds_check CHECK (age_seconds IS NULL OR age_seconds >= 0),
    ADD CONSTRAINT characters_deaths_check CHECK (deaths IS NULL OR deaths >= 0);
