-- Preserve installed upgrades, including gathering-tool glyphs, with the account item snapshot.
ALTER TABLE character_items ADD COLUMN upgrade_ids INTEGER[] NOT NULL DEFAULT '{}';
