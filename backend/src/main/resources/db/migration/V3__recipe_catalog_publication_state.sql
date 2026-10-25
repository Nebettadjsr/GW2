-- Completion marker for the global recipe catalog publication. Recipe rows are committed in
-- batches before items and ingredients are fetched, so matching IDs alone cannot prove the
-- catalog is complete. A missing row or complete = false means "publication not finished".
CREATE TABLE recipe_catalog_publication (
    singleton    BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    complete     BOOLEAN NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
