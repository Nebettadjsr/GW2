package repo;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Idempotent schema bootstrap for the ordered material catalog and successful-sync marker. */
public final class MaterialStorageSchema {
    private MaterialStorageSchema() {}

    public static void ensure(Connection con) throws SQLException {
        // On a migrated database this bootstrap has nothing left to do and must not write schema:
        // the ordered material catalog is Flyway-owned from migration V1 on, and the singleton
        // account_materials_sync marker is retired in favour of per-account source state
        // (STORY-INFRA-004). Returning rather than failing keeps the global material catalog - which
        // is shared data, not account data - working on an upgraded database.
        if (ManagedSchema.isManaged(con)) return;
        try (Statement statement = con.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS material_categories (
                        category_id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        display_order INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS material_category_items (
                        category_id INTEGER NOT NULL REFERENCES material_categories(category_id) ON DELETE CASCADE,
                        position INTEGER NOT NULL,
                        item_id INTEGER NOT NULL,
                        PRIMARY KEY (category_id, position)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS account_materials_sync (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        fetched_at TIMESTAMPTZ NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO account_materials_sync (id, fetched_at)
                    SELECT 1, COALESCE(MAX(fetched_at), now()) FROM account_materials HAVING COUNT(*) > 0
                    ON CONFLICT (id) DO NOTHING
                    """);
        }
    }
}
