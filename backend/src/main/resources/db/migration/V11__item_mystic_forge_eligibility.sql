-- NULL means the public item's flags have not been fetched yet.
ALTER TABLE items ADD COLUMN no_mystic_forge BOOLEAN;

CREATE INDEX idx_recipe_ingredients_item ON recipe_ingredients (item_id);
