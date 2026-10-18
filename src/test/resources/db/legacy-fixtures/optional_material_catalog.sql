-- Optional variant from db/manual/material_storage.sql: the ordered material catalog (global).
CREATE TABLE material_categories (
    category_id   INTEGER PRIMARY KEY,
    name          TEXT    NOT NULL,
    display_order INTEGER NOT NULL
);

CREATE TABLE material_category_items (
    category_id INTEGER NOT NULL REFERENCES material_categories (category_id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    item_id     INTEGER NOT NULL,
    PRIMARY KEY (category_id, position)
);
