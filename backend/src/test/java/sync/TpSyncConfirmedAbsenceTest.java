package sync;

import api.tp.TpPriceApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** KP-17: the ordinary Trading Post refresh stores confirmed absence as a no-data row (isolated schema, fake upstream). */
class TpSyncConfirmedAbsenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String schema = "test_tp_absence_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = admin.createStatement()) { st.execute("CREATE SCHEMA " + schema); }
        admin.setSchema(schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        TpSync.upstream = new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) throws Exception { return TpPriceApi.fetchBatch(b); }
            public JsonNode fetchSingle(int id) throws Exception { return TpPriceApi.fetchSingle(id); }
            public Set<Integer> fetchAllTradeableItemIds() throws Exception { return TpPriceApi.fetchAllTradeableItemIds(); }
        };
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        try (Statement st = admin.createStatement()) { st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        finally { admin.close(); }
    }

    private void exec(String sql) throws Exception { try (Statement st = admin.createStatement()) { st.execute(sql); } }

    private String scalar(String sql) throws Exception {
        try (Statement st = admin.createStatement(); ResultSet rs = st.executeQuery(sql)) { return rs.next() ? rs.getString(1) : null; }
    }

    private static JsonNode json(String s) {
        try { return JSON.readTree(s); } catch (Exception e) { throw new AssertionError(e); }
    }

    private static String quote(int id, int buy) {
        return "{\"id\":" + id + ",\"buys\":{\"quantity\":1,\"unit_price\":" + buy + "},\"sells\":{\"quantity\":2,\"unit_price\":9}}";
    }

    private static TpSync.TpUpstream scripted(int status, String arrayJson, IntFunction<String> single) {
        return new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) {
                JsonNode arr = json(arrayJson);
                Set<Integer> ret = new HashSet<>();
                if (arr.isArray()) for (JsonNode n : arr) ret.add(n.get("id").asInt());
                return new TpPriceApi.BatchResult(status, arr, ret);
            }
            public JsonNode fetchSingle(int id) { String r = single.apply(id); return r == null ? null : json(r); }
            public Set<Integer> fetchAllTradeableItemIds() { return Set.of(); }
        };
    }

    private void seed(String fetchedAt) throws Exception {
        exec("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, sell_quantity, sell_unit_price, fetched_at) "
                + "VALUES (1,1,111,1,111," + fetchedAt + "),(2,1,222,1,222," + fetchedAt + ")");
    }

    private static final String ROWS = "SELECT string_agg(coalesce(buy_unit_price::text,'N')||'/'||coalesce(buy_quantity::text,'N')"
            + "||'/'||coalesce(sell_unit_price::text,'N')||'/'||coalesce(sell_quantity::text,'N'), ',' ORDER BY item_id) FROM tp_prices";

    @Test
    void batch404ThenSingle404ReplacesOldQuoteWithNoDataRow() throws Exception {
        seed("now() - interval '1 day'");
        TpSync.upstream = scripted(404, "[]", id -> id == 1 ? quote(1, 7) : null);
        TpSync.syncTpPrices(Set.of(1, 2));
        assertEquals("7/1/9/2,N/N/N/N", scalar(ROWS));
        assertEquals("0", scalar("SELECT count(*) FROM tp_prices WHERE fetched_at < now() - interval '1 hour'"));
    }

    @Test
    void partialBatchMissingIdThenSingle404ReplacesOldQuoteWithNoDataRow() throws Exception {
        seed("now() - interval '1 day'");
        TpSync.upstream = scripted(206, "[" + quote(1, 5) + "]", id -> null);
        TpSync.syncTpPrices(Set.of(1, 2));
        assertEquals("5/1/9/2,N/N/N/N", scalar(ROWS));
        assertEquals("0", scalar("SELECT count(*) FROM tp_prices WHERE fetched_at < now() - interval '1 hour'"));
    }

    @Test
    void olderNoDataObservationNeverReplacesNewerRow() throws Exception {
        seed("now() + interval '1 day'");
        TpSync.upstream = scripted(404, "[]", id -> null);
        TpSync.syncTpPrices(Set.of(1, 2));
        assertEquals("111/1/111/1,222/1/222/1", scalar(ROWS));
    }

    @Test
    void transientSingleFailureKeepsOldRowsAndPropagates() throws Exception {
        seed("now() - interval '1 day'");
        TpSync.upstream = scripted(404, "[]", id -> { if (id == 2) throw new IllegalStateException("HTTP 503"); return null; });
        assertThrows(IllegalStateException.class, () -> TpSync.syncTpPrices(Set.of(1, 2)));
        assertEquals("111/1/111/1,222/1/222/1", scalar(ROWS));
        assertEquals("2", scalar("SELECT count(*) FROM tp_prices WHERE fetched_at < now() - interval '1 hour'"));
    }

    @Test
    void fullCatalogStillStoresNoDataForConfirmedAbsence() throws Exception {
        seed("now() - interval '1 day'");
        exec("INSERT INTO tp_tradeable_items (item_id) VALUES (1),(2)");
        TpSync.upstream = scripted(404, "[]", id -> id == 1 ? quote(1, 7) : null);
        TpSync.syncAllTradeablePrices(api.TaskDeadline.start());
        assertEquals("7/1/9/2,N/N/N/N", scalar(ROWS));
    }
}
