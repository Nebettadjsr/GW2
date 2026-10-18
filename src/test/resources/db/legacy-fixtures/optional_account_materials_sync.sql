-- Optional variant from db/manual/material_storage.sql: the retired singleton material marker.
CREATE TABLE account_materials_sync (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    fetched_at TIMESTAMPTZ NOT NULL
);
