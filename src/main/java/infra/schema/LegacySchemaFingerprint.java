package infra.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The supported pre-migration schema inventory, and the preflight that recognizes it
 * (TARGET_ARCHITECTURE.md 7.3, acceptance criterion 3).
 *
 * <p>The expectations below are the documented fingerprint. They were established from the actual
 * deployed manual schema by a read-only catalog inspection (columns with their underlying types and
 * nullability, plus every constraint definition per table) and cross-checked against the SQL the
 * production code issues in {@code sync.*} and {@code repo.*}. They are not a design wish: a legacy
 * database is supported only when it matches this exactly.
 *
 * <p>{@link #REQUIRED_OBJECTS} must all be present. {@link #OPTIONAL_OBJECTS} are the known
 * variants - the tables the three {@code db/manual/*.sql} scripts add to databases created before
 * the features that need them, so a legacy database may or may not carry each one. A present
 * optional object is recorded as a variant; an absent one is recorded as such and, for the global
 * ones, created by an explicit adoption normalization step so the live schema still converges on V1.
 *
 * <p>Anything else - an unknown table or view, a missing or extra column, a changed type or
 * nullability, a different constraint set - is drift. Drift blocks adoption with a report of what
 * differs; it is never absorbed by broad {@code IF NOT EXISTS} statements, and nothing is deleted or
 * corrected to make it fit.
 */
public final class LegacySchemaFingerprint {

    /** Identifies the one supported legacy inventory this release knows how to adopt. */
    public static final String FINGERPRINT_ID = "LEGACY-MANUAL-2026-10";

    /** Ownership classification of a legacy object (TARGET_ARCHITECTURE.md 7.2). */
    public enum Scope { GLOBAL, ACCOUNT }

    /** One expected legacy relation: its columns and its complete constraint set. */
    public record ExpectedTable(String name, Scope scope, List<String> columns, Set<String> constraints) {}

    private static final List<ExpectedTable> REQUIRED = List.of(
            new ExpectedTable("items", Scope.GLOBAL,
                    List.of("item_id:int4:NO", "name:text:YES", "type:text:YES", "rarity:text:YES",
                            "level:int4:YES", "vendor_value:int4:YES", "fetched_at:timestamptz:YES",
                            "icon_url:text:YES", "icon_path:text:YES"),
                    Set.of("PRIMARY KEY (item_id)")),
            new ExpectedTable("recipes", Scope.GLOBAL,
                    List.of("recipe_id:int4:NO", "type:text:YES", "output_item_id:int4:YES",
                            "output_item_count:int4:YES", "time_to_craft_ms:int4:YES", "min_rating:int4:YES",
                            "chat_link:text:YES", "disciplines:_text:YES", "flags:_text:YES",
                            "guild_ingredients:jsonb:YES", "fetched_at:timestamptz:YES"),
                    Set.of("PRIMARY KEY (recipe_id)")),
            new ExpectedTable("recipe_ingredients", Scope.GLOBAL,
                    List.of("recipe_id:int4:NO", "item_id:int4:NO", "count:int4:YES"),
                    Set.of("PRIMARY KEY (recipe_id, item_id)",
                            "FOREIGN KEY (recipe_id) REFERENCES recipes(recipe_id) ON DELETE CASCADE",
                            "FOREIGN KEY (item_id) REFERENCES items(item_id)")),
            new ExpectedTable("tp_tradeable_items", Scope.GLOBAL,
                    List.of("item_id:int4:NO", "fetched_at:timestamptz:NO"),
                    Set.of("PRIMARY KEY (item_id)")),
            new ExpectedTable("tp_prices", Scope.GLOBAL,
                    List.of("item_id:int4:NO", "buy_quantity:int8:YES", "buy_unit_price:int4:YES",
                            "sell_quantity:int8:YES", "sell_unit_price:int4:YES", "fetched_at:timestamptz:YES"),
                    Set.of("PRIMARY KEY (item_id)")),
            new ExpectedTable("account_bank", Scope.ACCOUNT,
                    List.of("slot:int4:NO", "item_id:int4:YES", "count:int4:YES", "binding:text:YES",
                            "bound_to:text:YES", "charges:int4:YES", "stats_id:int4:YES",
                            "stats_attrs:jsonb:YES", "fetched_at:timestamptz:YES"),
                    Set.of("PRIMARY KEY (slot)")),
            new ExpectedTable("account_materials", Scope.ACCOUNT,
                    List.of("item_id:int4:NO", "category:int4:YES", "count:int4:YES",
                            "binding:text:YES", "fetched_at:timestamptz:YES"),
                    Set.of("PRIMARY KEY (item_id)")),
            new ExpectedTable("account_recipes", Scope.ACCOUNT,
                    List.of("recipe_id:int4:NO", "fetched_at:timestamptz:YES"),
                    Set.of("PRIMARY KEY (recipe_id)")),
            new ExpectedTable("characters", Scope.ACCOUNT,
                    List.of("character_id:int8:NO", "name:text:NO", "profession:text:YES", "race:text:YES",
                            "gender:text:YES", "level:int4:YES", "created_at_gw:timestamptz:YES",
                            "fetched_at:timestamptz:NO"),
                    Set.of("PRIMARY KEY (character_id)", "UNIQUE (name)")),
            new ExpectedTable("character_crafting", Scope.ACCOUNT,
                    List.of("character_id:int8:NO", "discipline:text:NO", "rating:int4:NO",
                            "is_active:bool:NO", "fetched_at:timestamptz:NO"),
                    Set.of("PRIMARY KEY (character_id, discipline)",
                            "FOREIGN KEY (character_id) REFERENCES characters(character_id) ON DELETE CASCADE")),
            new ExpectedTable("character_recipes", Scope.ACCOUNT,
                    List.of("character_id:int8:NO", "recipe_id:int4:NO", "fetched_at:timestamptz:NO"),
                    Set.of("PRIMARY KEY (character_id, recipe_id)",
                            "FOREIGN KEY (character_id) REFERENCES characters(character_id) ON DELETE CASCADE")),
            new ExpectedTable("character_items", Scope.ACCOUNT,
                    List.of("character_id:int8:NO", "location:text:NO", "bag_index:int4:YES",
                            "slot_index:int4:YES", "equipment_slot:text:YES", "item_id:int4:NO",
                            "count:int4:NO", "binding:text:YES", "bound_to:text:YES",
                            "fetched_at:timestamptz:NO"),
                    // Nullable location parts under PostgreSQL's default NULLS DISTINCT: this unique
                    // index is exactly why duplicate inventory slots are representable in legacy data.
                    Set.of("UNIQUE (character_id, location, bag_index, slot_index, equipment_slot)",
                            "FOREIGN KEY (character_id) REFERENCES characters(character_id) ON DELETE CASCADE")));

    private static final List<ExpectedTable> OPTIONAL = List.of(
            // db/manual/material_storage.sql - absent before ordered material catalog support.
            new ExpectedTable("material_categories", Scope.GLOBAL,
                    List.of("category_id:int4:NO", "name:text:NO", "display_order:int4:NO"),
                    Set.of("PRIMARY KEY (category_id)")),
            new ExpectedTable("material_category_items", Scope.GLOBAL,
                    List.of("category_id:int4:NO", "position:int4:NO", "item_id:int4:NO"),
                    Set.of("PRIMARY KEY (category_id, \"position\")",
                            "FOREIGN KEY (category_id) REFERENCES material_categories(category_id) ON DELETE CASCADE")),
            new ExpectedTable("account_materials_sync", Scope.ACCOUNT,
                    List.of("id:int4:NO", "fetched_at:timestamptz:NO"),
                    Set.of("PRIMARY KEY (id)", "CHECK ((id = 1))")),
            // db/manual/account_luck.sql - the one identity-aware legacy account table.
            new ExpectedTable("account_luck", Scope.ACCOUNT,
                    List.of("account_id:text:NO", "consumed_luck:int8:NO", "fetched_at:timestamptz:NO"),
                    Set.of("PRIMARY KEY (account_id)", "CHECK ((consumed_luck >= 0))")),
            // db/manual/account_sync_state.sql - unscoped per-source completion markers.
            new ExpectedTable("account_sync_state", Scope.ACCOUNT,
                    List.of("source:text:NO", "fetched_at:timestamptz:NO"),
                    Set.of("PRIMARY KEY (source)")));

    public static final List<ExpectedTable> REQUIRED_OBJECTS = REQUIRED;
    public static final List<ExpectedTable> OPTIONAL_OBJECTS = OPTIONAL;

    /**
     * The result of a preflight inspection.
     *
     * @param variants             present optional objects, in inventory order
     * @param absentOptional       known optional objects this database does not have
     * @param drift                every difference from the supported inventory; empty means supported
     */
    public record Inspection(String fingerprintId,
                             List<String> variants,
                             List<String> absentOptional,
                             List<String> drift) {

        public boolean supported() {
            return drift.isEmpty();
        }

        /** The legacy account objects actually present, which is what adoption must preserve. */
        public List<String> presentAccountObjects() {
            List<String> present = new ArrayList<>();
            for (ExpectedTable table : accountObjects()) {
                if (!absentOptional.contains(table.name())) present.add(table.name());
            }
            return present;
        }

        public String describe() {
            return "fingerprint " + fingerprintId
                    + "; variants present " + variants
                    + "; known optional objects absent " + absentOptional
                    + (drift.isEmpty() ? "; no drift" : "; drift " + drift);
        }
    }

    private LegacySchemaFingerprint() {}

    /** Every expected object, required first. */
    public static List<ExpectedTable> allObjects() {
        List<ExpectedTable> all = new ArrayList<>(REQUIRED);
        all.addAll(OPTIONAL);
        return all;
    }

    /** Expected account-owned objects, parents before children so a move keeps relations valid. */
    public static List<ExpectedTable> accountObjects() {
        return allObjects().stream().filter(t -> t.scope() == Scope.ACCOUNT).toList();
    }

    /** Reads the schema's actual shape and compares it with the supported inventory. Writes nothing. */
    public static Inspection inspect(Connection con) throws SQLException {
        Map<String, List<String>> actualColumns = readColumns(con);
        Map<String, Set<String>> actualConstraints = readConstraints(con);
        Set<String> actualRelations = readRelations(con);

        List<String> variants = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        List<String> drift = new ArrayList<>();

        for (ExpectedTable expected : REQUIRED) {
            if (!actualRelations.contains(expected.name())) {
                drift.add("required table " + expected.name() + " is missing");
                continue;
            }
            compare(expected, actualColumns, actualConstraints, drift);
        }
        for (ExpectedTable expected : OPTIONAL) {
            if (!actualRelations.contains(expected.name())) {
                absent.add(expected.name());
                continue;
            }
            variants.add(expected.name());
            compare(expected, actualColumns, actualConstraints, drift);
        }

        Set<String> known = new LinkedHashSet<>();
        allObjects().forEach(t -> known.add(t.name()));
        for (String relation : new TreeSet<>(actualRelations)) {
            if (!known.contains(relation)) drift.add("unknown relation " + relation);
        }

        return new Inspection(FINGERPRINT_ID, List.copyOf(variants), List.copyOf(absent), List.copyOf(drift));
    }

    private static void compare(ExpectedTable expected,
                                Map<String, List<String>> actualColumns,
                                Map<String, Set<String>> actualConstraints,
                                List<String> drift) {
        List<String> columns = actualColumns.getOrDefault(expected.name(), List.of());
        if (!expected.columns().equals(columns)) {
            drift.add(expected.name() + " columns expected " + expected.columns() + " but found " + columns);
        }
        Set<String> constraints = actualConstraints.getOrDefault(expected.name(), Set.of());
        if (!expected.constraints().equals(constraints)) {
            drift.add(expected.name() + " constraints expected " + new TreeSet<>(expected.constraints())
                    + " but found " + new TreeSet<>(constraints));
        }
    }

    private static Map<String, List<String>> readColumns(Connection con) throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT table_name, column_name, udt_name, is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                ORDER BY table_name, ordinal_position
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                columns.computeIfAbsent(rs.getString(1), key -> new ArrayList<>())
                        .add(rs.getString(2) + ":" + rs.getString(3) + ":" + rs.getString(4));
            }
        }
        return columns;
    }

    private static Map<String, Set<String>> readConstraints(Connection con) throws SQLException {
        Map<String, Set<String>> constraints = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT c.relname, pg_get_constraintdef(con.oid)
                FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                WHERE con.connamespace = current_schema()::regnamespace
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                constraints.computeIfAbsent(rs.getString(1), key -> new LinkedHashSet<>())
                        .add(normalize(rs.getString(2), con.getSchema()));
            }
        }
        return constraints;
    }

    /**
     * Drops the schema qualification a referenced table may carry, so the same legacy schema compares
     * equal whether it is inspected in {@code public} or in a disposable test schema.
     */
    private static String normalize(String definition, String schema) {
        return schema == null ? definition : definition.replace(schema + ".", "");
    }

    private static Set<String> readRelations(Connection con) throws SQLException {
        Set<String> relations = new LinkedHashSet<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) relations.add(rs.getString(1));
        }
        return relations;
    }
}
