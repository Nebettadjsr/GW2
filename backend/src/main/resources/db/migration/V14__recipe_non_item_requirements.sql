-- Typed non-item recipe requirements (currencies such as Research Notes). Kept apart from
-- recipe_ingredients so currency ids never collide with item ids and item-only economics are unchanged.
CREATE TABLE recipe_non_item_requirements (
    recipe_id INTEGER NOT NULL,
    kind      TEXT    NOT NULL,
    ref_id    INTEGER NOT NULL,
    count     INTEGER NOT NULL CHECK (count > 0),
    CONSTRAINT recipe_non_item_requirements_pkey PRIMARY KEY (recipe_id, kind, ref_id),
    CONSTRAINT fk_recipe_non_item_requirements_recipe FOREIGN KEY (recipe_id) REFERENCES recipes (recipe_id) ON DELETE CASCADE
);
