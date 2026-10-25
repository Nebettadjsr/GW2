package repo;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-015 acceptance criteria 2-4 and 6 through the production repository and publication
 * classes, against the production Flyway chain in a disposable PostgreSQL schema. Two accounts hold
 * overlapping item, recipe, character and slot identifiers; every assertion goes through a
 * repository method or {@link AccountSourcePublication}, not through hand-written reads.
 */
class AccountSourcePublicationTest {

    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-000000000001");
    private static final AccountScope B = AccountScope.of("20000000-0000-0000-0000-000000000002");
    private static final Instant T1 = Instant.parse("2026-10-12T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-12T11:00:00Z");

    private final String schema = "test_source_publication_" + UUID.randomUUID().toString().replace("-", "");
    private final AccountSourcePublication publication = new AccountSourcePublication();
    private final AccountSourceStateRepository states = new AccountSourceStateRepository();
    private final AccountRegistryRepository registry = new AccountRegistryRepository();

    private Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = connect();
        try (Statement s = admin.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        admin.setAutoCommit(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin == null) return;
        try (Statement s = admin.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    @Test
    void repositoryReadsReturnOnlyTheRequestedAccountsOverlappingRows() throws Exception {
        publishAll(A, 2, 11, 10, 400, 3);
        publishAll(B, 7, 19, 20, 300, 9);

        var bank = new BankRepository();
        assertEquals(2, bank.loadBankSlots(admin, A).get(0).count());
        assertEquals(7, bank.loadBankSlots(admin, B).get(0).count());
        assertEquals(1, bank.loadBankSlots(admin, A).size());

        assertEquals(10, new AccountLuckRepository().find(admin, A).orElseThrow().consumedLuck());
        assertEquals(20, new AccountLuckRepository().find(admin, B).orElseThrow().consumedLuck());

        var recipes = new RecipeRepository();
        assertEquals(java.util.Set.of(7319), recipes.loadKnownRecipeIds(admin, A));
        assertEquals(java.util.Set.of(7319), recipes.loadKnownRecipeIds(admin, B));

        var characters = new CharacterRepository();
        assertEquals(List.of("Shared"), characters.loadAllCharacterNames(admin, A));
        assertEquals(400, characters.loadAllCharacterCrafting(admin, A).get(0).rating);
        assertEquals(300, characters.loadAllCharacterCrafting(admin, B).get(0).rating);

        // material storage (11/19) + bank (2/7) + character bag (3/9), summed per account only
        var inventory = new InventoryRepository();
        assertEquals(11 + 2 + 3, inventory.loadOwnedInventory(admin, A).get(100));
        assertEquals(19 + 7 + 9, inventory.loadOwnedInventory(admin, B).get(100));
    }

    @Test
    void replacingAndRemovingOneAccountsRowsLeavesTheOtherUntouched() throws Exception {
        publishAll(A, 2, 11, 10, 400, 3);
        publishAll(B, 7, 19, 20, 300, 9);

        // A's next bank publication carries no slots: A's stale row goes, B's stays.
        publication.publish(admin, A, "bank", T2, (c, s, ts) -> deleteStale(c, "account_bank", s, ts));

        assertTrue(new BankRepository().loadBankSlots(admin, A).isEmpty());
        assertEquals(7, new BankRepository().loadBankSlots(admin, B).get(0).count());
        assertEquals(2, states.read(admin, A, "bank").orElseThrow().generation());
        assertEquals(1, states.read(admin, B, "bank").orElseThrow().generation());
    }

    @Test
    void successfulEmptyAndUnchangedPublicationsAdvanceTheGeneration() throws Exception {
        var first = publication.publish(admin, A, "bank", T1, (c, s, ts) -> upsertBank(c, s, ts, 2));
        var unchanged = publication.publish(admin, A, "bank", T2, (c, s, ts) -> upsertBank(c, s, ts, 2));
        var empty = publication.publish(admin, A, "bank", T3, (c, s, ts) -> deleteStale(c, "account_bank", s, ts));

        assertEquals(List.of(1L, 2L, 3L), List.of(first.generation(), unchanged.generation(), empty.generation()));
        var marker = states.read(admin, A, "bank").orElseThrow();
        assertEquals(3, marker.generation());
        assertEquals(T3, marker.completedAt());
        assertTrue(new BankRepository().loadBankSlots(admin, A).isEmpty());
    }

    @Test
    void failedPublicationRollsBackRowsMarkerAndActivityButLastGoodStays() throws Exception {
        publication.publish(admin, A, "bank", T1, (c, s, ts) -> upsertBank(c, s, ts, 2));
        var before = registry.find(admin, A).orElseThrow();

        assertThrows(IllegalStateException.class, () -> publication.publish(admin, A, "bank", T2, (c, s, ts) -> {
            deleteStale(c, "account_bank", s, ts);
            upsertBank(c, s, ts, 99);
            throw new IllegalStateException("mid-publication failure");
        }));

        assertEquals(2, new BankRepository().loadBankSlots(admin, A).get(0).count());
        assertEquals(1, states.read(admin, A, "bank").orElseThrow().generation());
        assertEquals(T1, states.read(admin, A, "bank").orElseThrow().completedAt());
        assertEquals(before.lastSuccessfulSyncAt(), registry.find(admin, A).orElseThrow().lastSuccessfulSyncAt());
    }

    @Test
    void anUnregisteredAccountWhosFirstPublicationFailsIsNotRegistered() throws Exception {
        assertThrows(IllegalStateException.class, () -> publication.publish(admin, B, "bank", T1, (c, s, ts) -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(registry.find(admin, B).isEmpty());
        assertTrue(states.read(admin, B, "bank").isEmpty());
    }

    @Test
    void independentSourcesPublishDespiteAnotherSourcesFailure() throws Exception {
        assertThrows(IllegalStateException.class, () -> publication.publish(admin, A, "bank", T1, (c, s, ts) -> {
            upsertBank(c, s, ts, 5);
            throw new IllegalStateException("bank failed");
        }));
        publication.publish(admin, A, "luck", T1, (c, s, ts) ->
                new AccountLuckRepository().save(c, s, 10, T1));

        assertTrue(states.read(admin, A, "bank").isEmpty());
        assertEquals(1, states.read(admin, A, "luck").orElseThrow().generation());
        assertTrue(new BankRepository().loadBankSlots(admin, A).isEmpty());
        assertEquals(10, new AccountLuckRepository().find(admin, A).orElseThrow().consumedLuck());
    }

    @Test
    void sameAccountWritersSerializeWhileAnotherAccountProceeds() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Connection first = connect(); Connection second = connect(); Connection other = connect()) {
            first.setSchema(schema);
            second.setSchema(schema);
            other.setSchema(schema);

            var firstWriter = CompletableFuture.supplyAsync(() -> {
                try {
                    return publication.publish(first, A, "bank", T1, (c, s, ts) -> {
                        upsertBank(c, s, ts, 2);
                        holding.countDown();
                        if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("not released");
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(holding.await(30, TimeUnit.SECONDS));

            var secondWriter = CompletableFuture.supplyAsync(() -> {
                try {
                    return publication.publish(second, A, "bank", T2, (c, s, ts) -> upsertBank(c, s, ts, 3));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            awaitBlockedWriter();
            assertFalse(secondWriter.isDone(), "same-account writer must queue behind the first");

            // an unrelated account publishes while A's first writer still holds its transaction open
            var unrelated = publication.publish(other, B, "bank", T1, (c, s, ts) -> upsertBank(c, s, ts, 7));
            assertEquals(1, unrelated.generation());
            assertFalse(secondWriter.isDone());

            release.countDown();
            assertEquals(1, firstWriter.get(30, TimeUnit.SECONDS).generation());
            assertEquals(2, secondWriter.get(30, TimeUnit.SECONDS).generation());
        }
        assertEquals(3, new BankRepository().loadBankSlots(admin, A).get(0).count());
        assertEquals(2, states.read(admin, A, "bank").orElseThrow().generation());
        assertEquals(7, new BankRepository().loadBankSlots(admin, B).get(0).count());
    }

    @Test
    void accountsWhoseGuidsFoldToTheSameBitsDoNotSerialize() throws Exception {
        // msb^lsb is identical for both, so any 32/64-bit XOR-folded lock key would collide
        AccountScope collider = new AccountScope(new java.util.UUID(
                A.accountId().getMostSignificantBits() ^ 0x5A5AL, A.accountId().getLeastSignificantBits() ^ 0x5A5AL));
        assertNotEquals(A.accountId(), collider.accountId());
        assertNotEquals(AccountSourcePublication.writerLockKey(A), AccountSourcePublication.writerLockKey(collider));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Connection first = connect(); Connection second = connect()) {
            first.setSchema(schema);
            second.setSchema(schema);
            var firstWriter = CompletableFuture.supplyAsync(() -> {
                try {
                    return publication.publish(first, A, "bank", T1, (c, s, ts) -> {
                        upsertBank(c, s, ts, 2);
                        holding.countDown();
                        if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("not released");
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(holding.await(30, TimeUnit.SECONDS));
            var other = publication.publish(second, collider, "bank", T1, (c, s, ts) -> upsertBank(c, s, ts, 9));
            assertEquals(1, other.generation());
            assertFalse(firstWriter.isDone());
            release.countDown();
            assertEquals(1, firstWriter.get(30, TimeUnit.SECONDS).generation());
        }
        assertEquals(2, new BankRepository().loadBankSlots(admin, A).get(0).count());
        assertEquals(9, new BankRepository().loadBankSlots(admin, collider).get(0).count());
    }

    @Test
    void activityIsMonotonicUnknownUntilUsedAndOnlySuccessfulPublicationMovesSyncTime() throws Exception {
        registry.register(admin, A);
        var fresh = registry.find(admin, A).orElseThrow();
        assertNotNull(fresh.createdAt());
        assertNull(fresh.lastSeenAt());
        assertNull(fresh.lastSuccessfulSyncAt());

        registry.recordAuthorizedUse(admin, A, T2);
        registry.recordAuthorizedUse(admin, A, T1);
        assertEquals(T2, registry.find(admin, A).orElseThrow().lastSeenAt());

        publication.publish(admin, A, "luck", T3, (c, s, ts) -> new AccountLuckRepository().save(c, s, 1, T3));
        publication.publish(admin, A, "luck", T1, (c, s, ts) -> new AccountLuckRepository().save(c, s, 1, T1));
        assertEquals(T3, registry.find(admin, A).orElseThrow().lastSuccessfulSyncAt());
        assertEquals(T2, registry.find(admin, A).orElseThrow().lastSeenAt());
        assertNull(registry.find(admin, B).map(AccountRegistryRepository.Activity::lastSeenAt).orElse(null));
    }

    @Test
    void materialStorageWithoutAMarkerStaysUnsynchronizedAndReadingCreatesNone() throws Exception {
        registry.register(admin, A);
        assertThrows(SQLException.class, () -> new MaterialStorageRepository().loadMaterialStorage(admin, A));
        assertTrue(states.read(admin, A, AccountSyncStateRepository.MATERIALS).isEmpty());
    }

    @Test
    void repeatableReadSnapshotKeepsOneAccountsRowsAndGenerationsWhilePublishingAndSharesGlobalItems()
            throws Exception {
        update(admin, "INSERT INTO items (item_id, name, rarity) VALUES (100, 'Shared Ore', 'Fine')");
        publication.publish(admin, A, "bank", T1, (c, s, ts) -> upsertBank(c, s, ts, 2));
        publication.publish(admin, B, "bank", T1, (c, s, ts) -> upsertBank(c, s, ts, 7));

        var reader = new application.CalculationInputVersions.DatabaseAccountSourceStateReader();
        try (Connection snapshot = connect()) {
            snapshot.setSchema(schema);
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var bank = new BankRepository();
            assertEquals(2, bank.loadBankSlots(snapshot, A).get(0).count());
            assertEquals(1, reader.read(snapshot, A, java.util.Set.of("bank")).get("bank").generation());

            publication.publish(admin, A, "bank", T2, (c, s, ts) -> upsertBank(c, s, ts, 50));

            assertEquals(2, bank.loadBankSlots(snapshot, A).get(0).count());
            assertEquals(1, reader.read(snapshot, A, java.util.Set.of("bank")).get("bank").generation());
            // the shared item row is reused, not copied, for both accounts
            assertEquals("Fine", bank.loadBankSlots(snapshot, A).get(0).rarity());
            assertEquals("Fine", bank.loadBankSlots(snapshot, B).get(0).rarity());
            assertEquals(1, reader.read(snapshot, B, java.util.Set.of("bank")).get("bank").generation());
            snapshot.rollback();
        }
        assertEquals(50, new BankRepository().loadBankSlots(admin, A).get(0).count());
        assertEquals(2, reader.read(admin, A, java.util.Set.of("bank")).get("bank").generation());
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** Publishes every source family for one account with the given distinguishing values. */
    private void publishAll(AccountScope scope, int bank, int material, long luck, int rating, int bag)
            throws Exception {
        publication.publish(admin, scope, "bank", T1, (c, s, ts) -> upsertBank(c, s, ts, bank));
        publication.publish(admin, scope, AccountSyncStateRepository.MATERIALS, T1, (c, s, ts) -> {
            update(c, "INSERT INTO account_materials (account_id, item_id, count, fetched_at) VALUES (?, 100, ?, ?)",
                    s.accountId(), material, ts);
        });
        publication.publish(admin, scope, "luck", T1, (c, s, ts) -> new AccountLuckRepository().save(c, s, luck, T1));
        publication.publish(admin, scope, "recipes", T1, (c, s, ts) ->
                update(c, "INSERT INTO account_recipes (account_id, recipe_id, fetched_at) VALUES (?, 7319, ?)",
                        s.accountId(), ts));
        publication.publish(admin, scope, "characters", T1, (c, s, ts) -> {
            update(c, "INSERT INTO characters (account_id, character_id, name) VALUES (?, 41, 'Shared')", s.accountId());
            update(c, "INSERT INTO character_crafting (account_id, character_id, discipline, rating) VALUES (?, 41, 'Artificer', ?)",
                    s.accountId(), rating);
            update(c, "INSERT INTO character_recipes (account_id, character_id, recipe_id) VALUES (?, 41, 7319)",
                    s.accountId());
            update(c, "INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count) VALUES (?, 41, 'bag', 0, 0, 100, ?)",
                    s.accountId(), bag);
        });
    }

    private static void upsertBank(Connection c, AccountScope s, java.sql.Timestamp ts, int count) throws SQLException {
        update(c, """
                INSERT INTO account_bank (account_id, slot, item_id, count, fetched_at) VALUES (?, 1, 100, ?, ?)
                ON CONFLICT (account_id, slot) DO UPDATE SET item_id = EXCLUDED.item_id,
                    count = EXCLUDED.count, fetched_at = EXCLUDED.fetched_at
                """, s.accountId(), count, ts);
        deleteStale(c, "account_bank", s, ts);
    }

    private static void deleteStale(Connection c, String table, AccountScope s, java.sql.Timestamp ts)
            throws SQLException {
        update(c, "DELETE FROM " + table + " WHERE account_id = ? AND fetched_at < ?", s.accountId(), ts);
    }

    private static void update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    /** Waits until PostgreSQL reports a backend blocked on a lock; no sleep-and-hope. */
    private void awaitBlockedWriter() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Statement s = admin.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")) {
                rs.next();
                if (rs.getInt(1) > 0) return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no writer ever waited on the account lock");
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
    }
}
