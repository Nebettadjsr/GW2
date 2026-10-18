-- Deterministic legacy content for adoption checks. Recreatable from scratch, with no dependency on
-- any developer's database state. Every instant is an explicit UTC literal so a content checksum is
-- reproducible across sessions.

-- Global (shared) data: must survive adoption untouched and stay in the live schema.
INSERT INTO items (item_id, name, type, rarity, level, vendor_value, fetched_at, icon_url, icon_path)
VALUES (100, 'Copper Ore', 'CraftingMaterial', 'Basic', 0, 2, '2026-01-02T03:04:05Z', 'https://example.invalid/100.png', NULL),
       (200, 'Copper Ingot', 'CraftingMaterial', 'Basic', 0, 4, '2026-01-02T03:04:05Z', NULL, NULL),
       (300, 'Bronze Ingot', 'CraftingMaterial', 'Basic', 0, 8, '2026-01-02T03:04:05Z', NULL, NULL);

INSERT INTO recipes (recipe_id, type, output_item_id, output_item_count, time_to_craft_ms, min_rating,
                     chat_link, disciplines, flags, guild_ingredients, fetched_at)
VALUES (9001, 'Refinement', 200, 1, 1000, 0, '[&CSkAAAA=]', ARRAY['Weaponsmith'], ARRAY['AutoLearned'], NULL,
        '2026-01-02T03:04:05Z');

INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (9001, 100, 2);

INSERT INTO tp_tradeable_items (item_id, fetched_at) VALUES (100, '2026-01-03T00:00:00Z');
INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, sell_quantity, sell_unit_price, fetched_at)
VALUES (100, 500, 12, 400, 15, '2026-01-03T00:00:00Z');

-- Account data: every row below must end up preserved in the archive, and none of it may become
-- owned by any account without source-specific provenance.
INSERT INTO account_bank (slot, item_id, count, binding, bound_to, charges, stats_id, stats_attrs, fetched_at)
VALUES (0, 100, 250, NULL, NULL, NULL, NULL, NULL, '2026-02-01T10:00:00Z'),
       (1, 200, 10, 'Account', 'Someone', NULL, NULL, NULL, '2026-02-01T10:00:00Z'),
       (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '2026-02-01T10:00:00Z');

INSERT INTO account_materials (item_id, category, count, binding, fetched_at)
VALUES (100, 5, 1000, NULL, '2026-02-02T11:00:00Z'),
       (200, 5, 20, 'Account', '2026-02-02T11:00:00Z');

INSERT INTO account_recipes (recipe_id, fetched_at) VALUES (9001, '2026-02-03T12:00:00Z');

INSERT INTO characters (name, profession, race, gender, level, created_at_gw, fetched_at)
VALUES ('Legacy Hero', 'Warrior', 'Human', 'Male', 80, '2024-05-06T07:08:09Z', '2026-02-04T13:00:00Z'),
       ('Legacy Sidekick', 'Ranger', 'Sylvari', 'Female', 42, '2025-06-07T08:09:10Z', '2026-02-04T13:00:00Z');

INSERT INTO character_crafting (character_id, discipline, rating, is_active, fetched_at)
SELECT character_id, 'Weaponsmith', 400, TRUE, '2026-02-04T13:00:00Z' FROM characters WHERE name = 'Legacy Hero';

INSERT INTO character_recipes (character_id, recipe_id, fetched_at)
SELECT character_id, 9001, '2026-02-04T13:00:00Z' FROM characters WHERE name = 'Legacy Hero';

-- Two inventory rows the legacy representation cannot tell apart: the unique index spans nullable
-- bag/slot/equipment columns under PostgreSQL's default NULLS DISTINCT, so both were storable.
INSERT INTO character_items (character_id, location, bag_index, slot_index, equipment_slot, item_id, count,
                             binding, bound_to, fetched_at)
SELECT character_id, 'Equipped', NULL, NULL, NULL, 300, 1, 'Character', 'Legacy Hero', '2026-02-04T13:00:00Z'
FROM characters WHERE name = 'Legacy Hero';
INSERT INTO character_items (character_id, location, bag_index, slot_index, equipment_slot, item_id, count,
                             binding, bound_to, fetched_at)
SELECT character_id, 'Equipped', NULL, NULL, NULL, 200, 1, 'Character', 'Legacy Hero', '2026-02-04T13:00:00Z'
FROM characters WHERE name = 'Legacy Hero';
-- One unambiguous inventory row beside them.
INSERT INTO character_items (character_id, location, bag_index, slot_index, equipment_slot, item_id, count,
                             binding, bound_to, fetched_at)
SELECT character_id, 'Inventory', 0, 3, NULL, 100, 25, NULL, NULL, '2026-02-04T13:00:00Z'
FROM characters WHERE name = 'Legacy Hero';
