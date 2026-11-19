-- Recipe ids the last complete publication observed upstream but deliberately did not store because
-- they carry a requirement kind this application cannot honour (for example a guild upgrade). Safe sync
-- compares local ids plus these against the upstream catalog, so an unchanged catalog is skipped
-- instead of re-fetched on every run. A disposable cache field written only with complete = true;
-- NULL (for example from an imported snapshot) reads as none.
ALTER TABLE recipe_catalog_publication
    ADD COLUMN skipped_recipe_ids INTEGER[] DEFAULT '{}';
