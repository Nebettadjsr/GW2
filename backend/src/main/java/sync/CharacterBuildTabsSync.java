package sync;

import model.AccountScope;
import parser.CharacterBuildTabsParser;
import parser.CharacterBuildTabsParser.Skills;
import parser.CharacterBuildTabsParser.Tab;
import repo.AccountSourcePublication;
import repo.AccountSourceStateRepository;
import repo.AccountSyncStateRepository;
import repo.Db;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independently refreshed saved-build-tab coverage over the already-published {@code CHARACTERS} roster
 * (STORY-CHAR-007). Same shape as {@link CharacterEquipmentTabsSync}: capture the published roster and
 * generation, fetch and validate every character outside any publication transaction, then publish rows,
 * covered generation and the {@code CHARACTER_BUILD_TABS} marker in one transaction that first re-verifies
 * the parent generation. It never fetches or advances {@code CHARACTERS} and writes no stock.
 */
public final class CharacterBuildTabsSync {

    /**
     * Schema version the build-tabs request is pinned to. This is the same documented pin the equipment
     * tabs use ({@link CharacterEquipmentTabsSync#SCHEMA_VERSION}); the fields parsed here (tab,
     * is_active, build.name/profession/specializations/skills/aquatic_skills/legends/aquatic_legends/pets)
     * are the ones the GW2 wiki documents for that version. It is documentation- and fixture-backed only:
     * no live response was checked, and a fixture cannot prove live version support.
     */
    public static final String SCHEMA_VERSION = CharacterEquipmentTabsSync.SCHEMA_VERSION;

    public static final class PrerequisiteUnavailableException extends IllegalStateException {
        public PrerequisiteUnavailableException() {
            super("Build tabs need a published character roster; none is available");
        }
    }

    public static final class ParentChangedException extends IllegalStateException {
        public ParentChangedException() {
            super("The character roster changed while build tabs were fetched; nothing was published");
        }
    }

    record Capture(long parentGeneration, List<String> names) {}

    private CharacterBuildTabsSync() {}

    static String url(String characterName) {
        String enc = URLEncoder.encode(characterName, StandardCharsets.UTF_8).replace("+", "%20");
        return "https://api.guildwars2.com/v2/characters/" + enc + "/buildtabs?tabs=all&v=" + SCHEMA_VERSION;
    }

    static void sync(AccountScope scope, AccountSync.AuthFetcher fetcher) throws Exception {
        AccountSync.requireCredentialOwner(scope, fetcher);
        Capture capture;
        try (Connection con = Db.open()) {
            capture = capture(con, scope);
        }
        Map<String, List<Tab>> fetched = new LinkedHashMap<>();
        for (String name : capture.names()) {
            fetched.put(name, CharacterBuildTabsParser.parse(fetcher.get(url(name))));
        }
        try (Connection con = Db.open()) {
            publish(con, scope, capture, fetched, java.time.Clock.systemUTC());
        }
    }

    static Capture capture(Connection con, AccountScope scope) throws SQLException {
        con.setReadOnly(true);
        con.setAutoCommit(false);
        con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try {
            var parent = new AccountSourceStateRepository().read(con, scope, AccountSyncStateRepository.CHARACTERS);
            if (parent.isEmpty()) throw new PrerequisiteUnavailableException();
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT name FROM characters WHERE account_id = ? ORDER BY name, character_id")) {
                ps.setObject(1, scope.accountId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) names.add(rs.getString(1));
                }
            }
            return new Capture(parent.get().generation(), names);
        } finally {
            con.rollback();
            con.setReadOnly(false);
        }
    }

    static AccountSourcePublication.Published publish(Connection con, AccountScope scope, Capture capture,
                                                      Map<String, List<Tab>> fetched, java.time.Clock clock)
            throws Exception {
        return new AccountSourcePublication(clock).publish(
                con, scope, AccountSyncStateRepository.CHARACTER_BUILD_TABS,
                (tx, owner, runTs) -> {
                    var current = new AccountSourceStateRepository()
                            .read(tx, owner, AccountSyncStateRepository.CHARACTERS);
                    if (current.isEmpty() || current.get().generation() != capture.parentGeneration()) {
                        throw new ParentChangedException();
                    }
                    try (PreparedStatement ps = tx.prepareStatement(
                            "DELETE FROM character_build_tabs WHERE account_id = ?")) {
                        ps.setObject(1, owner.accountId());
                        ps.executeUpdate();
                    }
                    for (var character : fetched.entrySet()) {
                        long characterId = characterId(tx, owner, character.getKey());
                        for (Tab tab : character.getValue()) insertTab(tx, owner, characterId, tab, runTs);
                    }
                    try (PreparedStatement ps = tx.prepareStatement("""
                            INSERT INTO character_build_coverage (account_id, parent_generation) VALUES (?, ?)
                            ON CONFLICT (account_id) DO UPDATE SET parent_generation = EXCLUDED.parent_generation
                            """)) {
                        ps.setObject(1, owner.accountId());
                        ps.setLong(2, capture.parentGeneration());
                        ps.executeUpdate();
                    }
                });
    }

    private static long characterId(Connection tx, AccountScope owner, String name) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT character_id FROM characters WHERE account_id = ? AND name = ?")) {
            ps.setObject(1, owner.accountId());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ParentChangedException();
                return rs.getLong(1);
            }
        }
    }

    private static void insertTab(Connection tx, AccountScope owner, long characterId, Tab tab, Timestamp runTs)
            throws SQLException {
        var b = tab.build();
        try (PreparedStatement ps = tx.prepareStatement("""
                INSERT INTO character_build_tabs
                  (account_id, character_id, tab_number, name, profession, is_active,
                   land_heal, land_elite, land_utilities, aquatic_heal, aquatic_elite, aquatic_utilities,
                   land_legends, aquatic_legends, land_pets, aquatic_pets, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int i = 1;
            ps.setObject(i++, owner.accountId());
            ps.setLong(i++, characterId);
            ps.setInt(i++, tab.number());
            ps.setString(i++, b.name());
            ps.setString(i++, b.profession());
            ps.setBoolean(i++, tab.active());
            i = skills(tx, ps, i, b.skills());
            i = skills(tx, ps, i, b.aquaticSkills());
            ps.setArray(i++, array(tx, "text", b.legends()));
            ps.setArray(i++, array(tx, "text", b.aquaticLegends()));
            ps.setArray(i++, array(tx, "integer", b.pets()));
            ps.setArray(i++, array(tx, "integer", b.aquaticPets()));
            ps.setTimestamp(i, runTs);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("""
                INSERT INTO character_build_tab_specializations
                  (account_id, character_id, tab_number, position, specialization_id, traits)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            int position = 1;
            for (var s : b.specializations()) {
                ps.setObject(1, owner.accountId());
                ps.setLong(2, characterId);
                ps.setInt(3, tab.number());
                ps.setInt(4, position++);
                if (s.id() == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, s.id());
                ps.setArray(6, array(tx, "integer", s.traits()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Binds heal, elite, utilities (parameters i..i+2) and returns the next parameter index. */
    private static int skills(Connection tx, PreparedStatement ps, int i, Skills s) throws SQLException {
        setNullableInt(ps, i++, s.heal());
        setNullableInt(ps, i++, s.elite());
        ps.setArray(i++, array(tx, "integer", s.utilities()));
        return i;
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer v) throws SQLException {
        if (v == null) ps.setNull(index, java.sql.Types.INTEGER); else ps.setInt(index, v);
    }

    private static java.sql.Array array(Connection tx, String type, List<?> values) throws SQLException {
        return values == null ? null : tx.createArrayOf(type, values.toArray());
    }
}
