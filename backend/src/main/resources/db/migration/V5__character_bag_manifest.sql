-- Bag layout manifest (STORY-CHAR-006). One row per authoritative bag position of a character,
-- published in the same CHARACTERS transaction as the roster and the occupied character_items rows.
-- Empty cells are never stored: they follow from capacity minus the occupied coordinates.
CREATE TABLE character_bags (
    account_id        UUID        NOT NULL,
    character_id      BIGINT      NOT NULL,
    bag_index         INTEGER     NOT NULL,
    installed         BOOLEAN     NOT NULL,
    container_item_id INTEGER,
    capacity          INTEGER,
    fetched_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT character_bags_pkey PRIMARY KEY (account_id, character_id, bag_index),
    CONSTRAINT character_bags_index_check CHECK (bag_index >= 0),
    CONSTRAINT character_bags_state_check CHECK (
        (installed AND container_item_id > 0 AND capacity > 0)
        OR (NOT installed AND container_item_id IS NULL AND capacity IS NULL)),
    CONSTRAINT fk_character_bags_parent
        FOREIGN KEY (account_id, character_id)
        REFERENCES characters (account_id, character_id) ON DELETE CASCADE
);

-- One row per account, written by every CHARACTERS publication that wrote a manifest. A CHARACTERS
-- generation published before this capability has no row, so an absent manifest can never read as
-- an empty layout. A published account with zero bags still has this row.
CREATE TABLE character_bag_layout_publication (
    account_id UUID        NOT NULL REFERENCES account_registry (account_id),
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT character_bag_layout_publication_pkey PRIMARY KEY (account_id)
);
