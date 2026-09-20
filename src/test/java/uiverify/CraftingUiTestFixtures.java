package uiverify;

import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

/**
 * STORY-UI-001: disposable-schema fixture lifecycle for crafting-view UI tests, reusing the same
 * per-test uniquely-named-schema pattern already established by the Layer 2 PostgreSQL
 * integration tests (docs/TEST_STRATEGY.md §31.2 - see e.g. {@code repo.RecipeRepositoryTest},
 * {@code repo.CharacterSelectionCraftingPlanIntegrationTest}). Never touches the developer's
 * normal database content: everything happens inside a schema created here and dropped in
 * {@link #dropSchemaAndClose()}.
 *
 * <p>Sets {@link repo.Db#TEST_SCHEMA_PROPERTY} so every {@code repo.Db.open()} call made from
 * inside the real application code (view -&gt; controller -&gt; repositories) - not just calls a
 * test can pass an explicit {@link Connection} to - resolves against this fixture schema.
 * Also points {@link craft.CraftingGraphCache} at a fresh temp file so the real crafting graph
 * cache in the project's working directory is never read or overwritten by a test run.
 *
 * <p>Intended for reuse by STORY-DOM-013/014/015's own real-view checks: extend the minimal
 * schema/seed below with whatever additional rows a specific selection/refresh/blocked-row
 * scenario needs.
 */
public final class CraftingUiTestFixtures implements AutoCloseable {

    public final String schema;
    private final Connection con;

    public CraftingUiTestFixtures() throws Exception {
        this.schema = "test_ui_" + UUID.randomUUID().toString().replace("-", "");
        this.con = DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));

        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);

        createTables();

        System.setProperty(repo.Db.TEST_SCHEMA_PROPERTY, schema);
        System.setProperty(craft.CraftingGraphCache.TEST_CACHE_FILE_PROPERTY,
                java.nio.file.Files.createTempFile("ui-test-crafting-graph-", ".json").toString());
    }

    private void createTables() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                CREATE TABLE recipes (
                    recipe_id         INTEGER PRIMARY KEY,
                    output_item_id    INTEGER NOT NULL,
                    output_item_count INTEGER NOT NULL,
                    min_rating        INTEGER NOT NULL,
                    disciplines       TEXT[],
                    flags             TEXT[]
                )
                """);
            st.execute("""
                CREATE TABLE recipe_ingredients (
                    recipe_id INTEGER NOT NULL REFERENCES recipes(recipe_id),
                    item_id   INTEGER NOT NULL,
                    count     INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE account_recipes (
                    recipe_id INTEGER PRIMARY KEY REFERENCES recipes(recipe_id)
                )
                """);
            st.execute("""
                CREATE TABLE characters (
                    character_id BIGSERIAL PRIMARY KEY,
                    name         TEXT NOT NULL UNIQUE
                )
                """);
            st.execute("""
                CREATE TABLE character_recipes (
                    character_id BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    recipe_id    INTEGER NOT NULL REFERENCES recipes(recipe_id)
                )
                """);
            st.execute("""
                CREATE TABLE character_crafting (
                    character_id  BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    discipline    TEXT   NOT NULL,
                    rating        INTEGER NOT NULL DEFAULT 0,
                    is_active     BOOLEAN NOT NULL DEFAULT false,
                    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (character_id, discipline)
                )
                """);
            st.execute("""
                CREATE TABLE character_items (
                    character_id    BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    location        TEXT NOT NULL,
                    bag_index       INTEGER,
                    slot_index      INTEGER,
                    equipment_slot  TEXT,
                    item_id         INTEGER NOT NULL,
                    count           INTEGER NOT NULL DEFAULT 1,
                    binding         TEXT,
                    bound_to        TEXT,
                    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
            st.execute("""
                CREATE TABLE account_materials (
                    item_id     INTEGER PRIMARY KEY,
                    category    INTEGER,
                    count       INTEGER,
                    binding     TEXT,
                    fetched_at  TIMESTAMPTZ
                )
                """);
            st.execute("""
                CREATE TABLE account_bank (
                    slot        INTEGER PRIMARY KEY,
                    item_id     INTEGER,
                    count       INTEGER,
                    binding     TEXT,
                    bound_to    TEXT,
                    charges     INTEGER,
                    stats_id    INTEGER,
                    stats_attrs JSONB,
                    fetched_at  TIMESTAMPTZ
                )
                """);
            st.execute("""
                CREATE TABLE items (
                    item_id     INTEGER PRIMARY KEY,
                    name        TEXT,
                    icon_path   TEXT
                )
                """);
            st.execute("""
                CREATE TABLE tp_prices (
                    item_id         INTEGER PRIMARY KEY,
                    buy_quantity    BIGINT,
                    buy_unit_price  INTEGER,
                    sell_quantity   BIGINT,
                    sell_unit_price INTEGER,
                    fetched_at      TIMESTAMPTZ
                )
                """);
        }
    }

    /** Runs {@code sql} against the fixture schema; for seeding rows in a specific test. */
    public void execute(String sql) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * Drops the fixture schema, closes the seeding connection, and clears the system properties
     * set in the constructor so a later, non-UI test run in the same JVM is unaffected.
     */
    public void dropSchemaAndClose() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
            System.clearProperty(repo.Db.TEST_SCHEMA_PROPERTY);
            System.clearProperty(craft.CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        }
    }

    @Override
    public void close() throws Exception {
        dropSchemaAndClose();
    }
}
