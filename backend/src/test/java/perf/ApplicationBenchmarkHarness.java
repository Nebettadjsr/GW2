package perf;

import application.AccountRefreshService;
import application.BackgroundTasks;
import application.CraftingGraphRebuildService;
import application.GlobalDataRefreshService;
import application.ScopedAccountRefreshCoordinator;
import application.TradingPostPriceRefreshService;
import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountProofPort;
import application.access.InvalidCredentialException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import repo.CraftingGraphCache;
import repo.Db;
import repo.EnvConfig;
import repo.ItemRepository;
import repo.tp.TpPriceRepository;
import sync.GlobalDataRefreshGateway;
import sync.TradingPostPriceRefreshGateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * STORY-PERF-005 measurement workloads against the real application: real HTTP, controllers,
 * application services, scoped repositories, the production Flyway chain and the shared crafting
 * graph cache, on a disposable PostgreSQL schema (never the public schema). Only the outside edges
 * are replaced - the proof port, the upstream account GETs, and the global/price refresh gateways
 * that would otherwise call ArenaNet - by deterministic fixtures, so nothing leaves the machine.
 *
 * <p>Nothing here asserts a performance threshold: a failing workload becomes a diagnostic in the
 * report. Subclasses choose the fixture {@link Sizes}; they own whether the run is a measurement
 * ({@code ApplicationResourceBenchmarkIT}) or a quick boundary check
 * ({@code ApplicationBenchmarkBoundaryIT}).
 */
@SpringBootTest(classes = web.Gw2ApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gw2.account-luck.schema-init=false", "gw2.account-access.enforce=true",
                "gw2.global-refresh.initial-delay-ms=3600000"})
@Import(ApplicationBenchmarkHarness.Fixtures.class)
abstract class ApplicationBenchmarkHarness {

    static final String FIXTURE_VERSION = "perf-fixtures-2";
    static final String ACCOUNT = "AAAAAAAA-1111-2222-3333-444444444444";
    static final String KEY = "perf-key";
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SCHEMA = "perf_" + UUID.randomUUID().toString().replace("-", "");

    /** Fixture and load sizes; the same code path runs at any size. */
    record Sizes(int rawItems, int smallRecipes, int largeRecipes, int bankSlots, int typicalRequests,
                 int profitRepeats, int threads, int requestsPerThread, long idleMillis) {}

    abstract Sizes sizes();

    // ---- fixture state shared with the fake outside edges (static: Spring builds the fakes) -------
    static volatile Sizes active;
    /** Raised between the full and the incremental sync so the upstream fixture differs slightly. */
    static final AtomicInteger UPSTREAM_EPOCH = new AtomicInteger();

    static int outputItem(int i) { return 10_000 + i; }
    static int recipeId(int i) { return 20_000 + i; }

    @LocalServerPort int port;
    @Autowired CraftingGraphCache graphCache;
    @Autowired org.springframework.core.env.Environment env;

    private final HttpClient client = HttpClient.newHttpClient();
    private final DatabaseProbe db = new DatabaseProbe(EnvConfig.require("DATABASE_URL"),
            EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"), SCHEMA);
    private String cookie;
    private String context;

    // ================================================================ fixtures (outside edges)

    @TestConfiguration
    static class Fixtures {
        @Bean @Primary
        AccountProofPort perfProofPort() {
            Set<AccountGrant> all = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES, AccountGrant.CHARACTERS,
                    AccountGrant.UNLOCKS, AccountGrant.PROGRESSION, AccountGrant.TRADINGPOST);
            return key -> {
                if (!KEY.equals(key) && !investigationKey(key)) throw new InvalidCredentialException("rejected");
                return new AccountProof(fixtureAccount(key), all, Set.of(), null);
            };
        }

        @Bean @Primary
        ScopedAccountRefreshCoordinator perfScopedCoordinator(BackgroundTasks tasks, AccountAccessService access) {
            AccountRefreshService refresh = new AccountRefreshService(new sync.AccountRefreshGateway() {
                @Override protected JsonNode fetchItemMetadata(java.util.List<Integer> ids) {
                    return JSON.createArrayNode(); // Synthetic metadata edge: never call ArenaNet.
                }
            });
            return new ScopedAccountRefreshCoordinator(refresh, tasks, access,
                    key -> url -> {
                        String path = url.substring(url.indexOf("/v2/") + 3);
                        JsonNode result = upstream(path);
                        if (investigationKey(key) && path.equals("/account")) {
                            return JSON.createObjectNode().put("id", fixtureAccount(key));
                        }
                        if (investigationKey(key) && path.equals("/account/bank")) {
                            int marker = Integer.parseInt(key.substring("investigation-".length())) + 1000;
                            ((com.fasterxml.jackson.databind.node.ObjectNode) result.get(0)).put("count", marker);
                        }
                        return result;
                    });
        }

        @Bean @Primary
        GlobalDataRefreshService perfGlobalRefresh(CraftingGraphCache cache) {
            return new GlobalDataRefreshService(new GlobalDataRefreshGateway() {
                @Override public void syncMaterialCategories() { }
                @Override public void syncMaterialItemMetadata() { }
                @Override public boolean syncTpTradeableItems() throws Exception {
                    writeInTestSchema(c -> upsertTradeable(c, active.rawItems(), active.largeRecipes()));
                    return true;
                }
                @Override public boolean syncAllRecipesGlobalSafe() throws Exception {
                    writeInTestSchema(c -> upsertRecipes(c, active.rawItems(), 0, active.largeRecipes()));
                    return true;
                }
            }, new CraftingGraphRebuildService(cache));
        }

        @Bean @Primary
        TradingPostPriceRefreshService perfPriceRefresh() {
            return new TradingPostPriceRefreshService(new TradingPostPriceRefreshGateway() {
                @Override public void syncTpPricesForDiscovery() throws Exception { upsertPrices(); }
                @Override public void syncTpPricesForProfit() throws Exception { upsertPrices(); }
                @Override public void syncTpPricesForDiscovery(model.AccountScope scope) throws Exception { upsertPrices(); }
                @Override public void syncTpPricesForProfit(model.AccountScope scope) throws Exception { upsertPrices(); }

                private void upsertPrices() throws Exception {
                    writeInTestSchema(c -> upsertTpPrices(c, active.rawItems(), active.largeRecipes()));
                }
            });
        }

        static JsonNode upstream(String path) throws Exception {
            Sizes s = active;
            int epoch = UPSTREAM_EPOCH.get();
            ArrayNode out = JSON.createArrayNode();
            switch (path) {
                case "/account" -> { return JSON.readTree("{\"id\":\"" + ACCOUNT + "\"}"); }
                case "/account/bank" -> {
                    for (int slot = 0; slot < s.bankSlots(); slot++) {
                        // every 20th slot differs between epochs: the incremental sync's delta
                        out.add(JSON.readTree("{\"id\":" + (1 + slot % s.rawItems()) + ",\"count\":"
                                + (10 + (slot % 20 == 0 ? epoch : 0)) + "}"));
                    }
                }
                case "/account/materials" -> {
                    for (int id = 1; id <= s.rawItems(); id++) {
                        out.add(JSON.readTree("{\"id\":" + id + ",\"category\":5,\"count\":"
                                + (100 + (id % 20 == 0 ? epoch : 0)) + "}"));
                    }
                }
                case "/account/recipes" -> {
                    for (int i = 0; i < s.largeRecipes(); i += 2) out.add(recipeId(i));
                }
                case "/account/luck" -> { return JSON.readTree("[{\"id\":\"luck\",\"value\":" + (10 + epoch) + "}]"); }
                case "/characters" -> { }
                default -> throw new java.io.IOException("unexpected fixture request " + path);
            }
            return out;
        }
    }

    // Test-only experiment identities. Production account proof and upstream adapters are untouched.
    static boolean investigationKey(String key) {
        return Boolean.getBoolean("gw2.investigation") && key != null && key.matches("investigation-[0-9]{1,3}");
    }

    static String fixtureAccount(String key) {
        return investigationKey(key)
                ? UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString() : ACCOUNT;
    }

    interface SqlWork { void run(Connection c) throws Exception; }

    static void writeInTestSchema(SqlWork work) throws Exception {
        try (Connection c = connect()) {
            c.setSchema(SCHEMA);
            c.setAutoCommit(false);
            work.run(c);
            c.commit();
        }
    }

    static Connection connect() throws Exception {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
    }

    static void upsertTradeable(Connection c, int raw, int recipes) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tp_tradeable_items (item_id) VALUES (?) ON CONFLICT (item_id) DO UPDATE SET fetched_at = now()")) {
            for (int id : allItemIds(raw, recipes)) { ps.setInt(1, id); ps.addBatch(); }
            ps.executeBatch();
        }
    }

    static int[] allItemIds(int raw, int recipes) {
        int[] ids = new int[raw + recipes];
        for (int i = 0; i < raw; i++) ids[i] = i + 1;
        for (int i = 0; i < recipes; i++) ids[raw + i] = outputItem(i);
        return ids;
    }

    static void upsertItems(Connection c, int raw, int recipes) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO items (item_id, name, type, rarity, level, vendor_value, fetched_at) "
                        + "VALUES (?, ?, 'CraftingMaterial', 'Basic', 0, 1, now()) ON CONFLICT (item_id) DO NOTHING")) {
            for (int id : allItemIds(raw, recipes)) {
                ps.setInt(1, id); ps.setString(2, "Perf fixture item " + id); ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void upsertTpPrices(Connection c, int raw, int recipes) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, sell_quantity, sell_unit_price, fetched_at)
                VALUES (?, 500, ?, 500, ?, now())
                ON CONFLICT (item_id) DO UPDATE SET buy_unit_price = EXCLUDED.buy_unit_price,
                    sell_unit_price = EXCLUDED.sell_unit_price, fetched_at = now()""")) {
            for (int id : allItemIds(raw, recipes)) {
                boolean isRaw = id <= raw;
                int base = isRaw ? 40 + (id % 40) * 3 : 3_000 + (id % 50) * 90;
                ps.setInt(1, id); ps.setInt(2, base); ps.setInt(3, base + base / 10); ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Recipes [from, to): each combines two raw materials and, past the first, an earlier output (a binary tree). */
    static void upsertRecipes(Connection c, int raw, int from, int to) throws Exception {
        try (PreparedStatement r = c.prepareStatement("""
                INSERT INTO recipes (recipe_id, type, output_item_id, output_item_count, time_to_craft_ms, min_rating,
                                     disciplines, flags, fetched_at)
                VALUES (?, 'Food', ?, 1, 1000, ?, ARRAY['Chef'], ARRAY[]::text[], now())
                ON CONFLICT (recipe_id) DO UPDATE SET fetched_at = now()""");
             PreparedStatement g = c.prepareStatement(
                     "INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            for (int i = from; i < to; i++) {
                r.setInt(1, recipeId(i)); r.setInt(2, outputItem(i)); r.setInt(3, (i % 4) * 100); r.addBatch();
                int a = i % raw + 1;
                int b = (i * 7 + 3) % raw + 1;
                if (b == a) b = b % raw + 1;
                ingredient(g, i, a, 2);
                ingredient(g, i, b, 3);
                if (i >= 1) ingredient(g, i, outputItem((i - 1) / 2), 1);
            }
            r.executeBatch();
            g.executeBatch();
        }
    }

    private static void ingredient(PreparedStatement g, int recipe, int item, int count) throws Exception {
        g.setInt(1, recipeId(recipe)); g.setInt(2, item); g.setInt(3, count); g.addBatch();
    }

    // ================================================================ run

    /** Runs every workload and returns the report. Errors become diagnostics, never exceptions. */
    MeasurementReport runAll() throws Exception {
        Sizes s = sizes();
        active = s;
        UPSTREAM_EPOCH.set(0);
        java.nio.file.Path graphFile = java.nio.file.Files.createTempFile("perf-crafting-graph", ".json");
        java.nio.file.Files.delete(graphFile);
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, graphFile.toString());
        try {
            createSchema(s);
            System.setProperty(Db.TEST_SCHEMA_PROPERTY, SCHEMA);
            login();
            MeasurementReport report = new MeasurementReport(commit(), Instant.now(), FIXTURE_VERSION, environment(s));
            report.add(measure("idle", "Application idle: no requests, no tasks.",
                    "Application JVM process; the load-generating client lives in the same JVM but sends nothing.",
                    1, rec -> Thread.sleep(s.idleMillis())));
            report.add(measure("account_sync_full",
                    "First account refresh (POST /api/sync/account) of an account with no stored data: bank, materials, recipes, Luck.",
                    "HTTP accept to task SUCCEEDED, through the scoped coordinator, repositories and PostgreSQL.", 1,
                    rec -> syncAccount(rec)));
            UPSTREAM_EPOCH.incrementAndGet();
            report.add(measure("account_sync_incremental",
                    "Repeat account refresh after about 5% of upstream rows changed.",
                    "Same as account_sync_full; the application always re-fetches and republishes whole sources.", 1,
                    rec -> syncAccount(rec)));
            report.add(measure("price_refresh",
                    "Trading Post price refresh for Crafting Profit (POST /api/prices/refresh).",
                    "HTTP accept to task SUCCEEDED; the refresh gateway is a fixture that writes " + (s.rawItems() + s.largeRecipes())
                            + " price rows through JDBC, so upstream fetch/parse cost is excluded.", 1,
                    rec -> asyncTask(rec, "POST", "/api/prices/refresh", "{\"variant\":\"PROFIT\"}")));
            seedRoster(); // outside any measured window: both profit cases need the same owned crafting roster
            report.add(measure("profit_small",
                    "Crafting Profit (All scope, account roster) over " + s.smallRecipes() + " recipes; first call, then repeats with changed settings.",
                    "HTTP POST /api/crafting/profit through service, calculation and JSON serialization.", s.profitRepeats() + 1,
                    rec -> profit(rec, s.profitRepeats())));
            report.add(measure("global_refresh",
                    "Global data refresh (POST /api/sync/global) that adds the remaining recipes and rebuilds the crafting graph.",
                    "HTTP accept to task SUCCEEDED including real graph rebuild; the upstream fetch is a fixture that writes "
                            + s.largeRecipes() + " recipes through JDBC.", 1,
                    rec -> asyncTask(rec, "POST", "/api/sync/global", "{}")));
            report.add(measure("profit_large",
                    "Crafting Profit (All scope, account roster) over " + s.largeRecipes() + " recipes; first call, then repeats with changed settings.",
                    "Same as profit_small.", s.profitRepeats() + 1, rec -> profit(rec, s.profitRepeats())));
            report.add(measure("http_typical",
                    "Sequential typical reads: system status, bank, materials, selector options.",
                    "HTTP GET through interceptor, controller, service, repository.", s.typicalRequests() * 4,
                    rec -> typical(rec, s.typicalRequests())));
            report.add(measure("database_reads",
                    "Repository reads of Trading Post quotes and item metadata for every fixture item.",
                    "Application repositories (TpPriceRepository, ItemRepository) over JDBC; no HTTP.", s.typicalRequests(),
                    rec -> repositoryReads(rec, s.typicalRequests())));
            report.add(measure("http_concurrent",
                    s.threads() + " concurrent clients each issuing a mix of profit, status, bank and selector requests.",
                    "HTTP, whole application, shared connection use.", s.threads() * s.requestsPerThread(),
                    rec -> concurrent(rec, s.threads(), s.requestsPerThread())));
            additionalWorkloads(report);
            return report;
        } finally {
            System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
            System.clearProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
            java.nio.file.Files.deleteIfExists(graphFile);
            try (Connection c = connect(); Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }

    /** Experiment hook, executed while the isolated schema remains alive. Normal benchmarks add none. */
    void additionalWorkloads(MeasurementReport report) throws Exception { }

    private void createSchema(Sizes s) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA " + SCHEMA);
        }
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD")).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration").load().migrate();
        writeInTestSchema(c -> {
            upsertItems(c, s.rawItems(), s.largeRecipes());
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO material_categories (category_id, name, display_order) VALUES (5, 'Fixture', 1)");
                st.execute("INSERT INTO material_category_items (category_id, position, item_id) "
                        + "SELECT 5, g, g FROM generate_series(1, " + s.rawItems() + ") g");
            }
            upsertTradeable(c, s.rawItems(), s.largeRecipes());
            upsertTpPrices(c, s.rawItems(), s.largeRecipes());
            upsertRecipes(c, s.rawItems(), 0, s.smallRecipes());
        });
        graphCache.invalidate();
    }

    /** One owned Chef 400 character, so All scope has a roster to craft with. Idempotent. */
    private void seedRoster() throws Exception {
        writeInTestSchema(c -> {
            try (Statement st = c.createStatement()) {
                String id = ACCOUNT.toLowerCase();
                st.execute("INSERT INTO account_registry (account_id) VALUES ('" + id + "') ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO characters (account_id, name, profession) VALUES ('" + id
                        + "', 'Aria', 'Guardian') ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO character_crafting (account_id, character_id, discipline, rating, is_active) "
                        + "SELECT account_id, character_id, 'Chef', 400, true FROM characters WHERE account_id = '" + id
                        + "' AND name = 'Aria' ON CONFLICT DO NOTHING");
            }
        });
    }

    // ================================================================ measurement wrapper

    /** What a workload body reports back to the measurement wrapper. */
    static final class Recorder {
        final List<Double> latenciesMillis = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicLong bytesOut = new AtomicLong();
        final AtomicLong bytesIn = new AtomicLong();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final List<String> diagnostics = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Double> jobMillis = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Double> repoLatenciesMillis = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Metric> extra = java.util.Collections.synchronizedList(new ArrayList<>());
    }

    static final long CONNECTION_SAMPLE_MILLIS = 25;
    /** Task-status poll interval: a task shorter than this is observed at this granularity. */
    static final long TASK_POLL_MILLIS = 50;

    interface Body { void run(Recorder rec) throws Exception; }

    MeasurementReport.Workload measure(String name, String description, String boundary, int iterations, Body body) {
        Recorder rec = new Recorder();
        List<Metric> metrics = new ArrayList<>();
        List<String> limits = new ArrayList<>(List.of(
                "Process figures cover the whole JVM, which also hosts the in-process load-generating client and fixture writers.",
                "Fixture data is synthetic (" + FIXTURE_VERSION + "); sizes are not a claim about real GW2 data volumes."));
        DatabaseProbe.Counters dbBefore = null;
        try {
            dbBefore = db.counters();
        } catch (Exception e) {
            rec.diagnostics.add("database counters unavailable before run: " + e);
        }
        ConnectionObserver observer = new ConnectionObserver(db::openObserverConnection, CONNECTION_SAMPLE_MILLIS);
        long cpu0 = ProcessProbe.cpuNanos();
        long wall0 = System.nanoTime();
        try (ProcessProbe.PeakSampler heap = new ProcessProbe.PeakSampler("heap", ProcessProbe::heapUsedBytes, 20);
             ProcessProbe.PeakSampler rss = new ProcessProbe.PeakSampler("rss", ProcessProbe::rssBytes, 20);
             observer) {
            try {
                body.run(rec);
            } catch (Throwable t) {
                rec.diagnostics.add("workload error: " + t);
            }
            long wallNanos = System.nanoTime() - wall0;
            long cpu1 = ProcessProbe.cpuNanos();
            collectProcess(metrics, cpu0, cpu1, wallNanos, heap.peak(), rss.peak(), name.equals("idle"));
            metrics.addAll(observer.metrics(rec.diagnostics, CONNECTION_SAMPLE_MILLIS));
            collectHttp(metrics, rec, wallNanos);
            collectRepository(metrics, rec, wallNanos);
            metrics.addAll(rec.extra);
            if (!rec.jobMillis.isEmpty()) {
                metrics.add(Metric.of("background_job_duration", rec.jobMillis.stream().mapToDouble(Double::doubleValue).sum()
                        / rec.jobMillis.size(), "ms", "HTTP accept to terminal task state (mean over runs)"));
                metrics.add(Metric.unavailable("background_job_cpu_time", "ms", "task",
                        "the task runs on shared pool threads; only the whole-process CPU of the workload window is attributable"));
                metrics.add(Metric.unavailable("background_job_memory", "bytes", "task",
                        "JVM heap is shared; only the workload-window peak above is observable"));
                metrics.add(frequency());
            }
        }
        DatabaseMetrics.collect(db, dbBefore, DatabaseProbe.SETTLE_MILLIS, metrics, rec.diagnostics);
        return new MeasurementReport.Workload(name, description, boundary, iterations, metrics, limits, rec.diagnostics);
    }

    private Metric frequency() {
        String interval = env.getProperty("gw2.global-refresh.interval-ms");
        if (interval == null) {
            return Metric.unavailable("background_job_frequency", "ms", "configuration", "no configured interval found");
        }
        return Metric.of("scheduled_global_refresh_interval", Double.parseDouble(interval), "ms",
                "configured value (gw2.global-refresh.interval-ms), not an observed frequency; account and price refreshes are demand-driven");
    }

    static void collectProcess(List<Metric> out, long cpu0, long cpu1, long wallNanos, long heapPeak,
                                       long rssPeak, boolean idle) {
        String boundary = "application JVM process";
        if (cpu0 >= 0 && cpu1 >= 0) {
            out.add(Metric.of("wall_time", Stats.nanosToMillis(wallNanos), "ms", boundary));
            out.add(Metric.of("cpu_time", Stats.nanosToMillis(cpu1 - cpu0), "ms", boundary + ", user+system, workload window"));
            out.add(Metric.of("cpu_utilization", Stats.cpuUtilizationPercent(cpu1 - cpu0, wallNanos, ProcessProbe.processors()),
                    "percent of all processors", boundary + ", workload window"));
        } else {
            out.add(Metric.of("wall_time", Stats.nanosToMillis(wallNanos), "ms", boundary));
            out.add(Metric.unavailable("cpu_time", "ms", boundary, "this JVM does not report process CPU time"));
            out.add(Metric.unavailable("cpu_utilization", "percent of all processors", boundary,
                    "this JVM does not report process CPU time"));
        }
        out.add(heapPeak >= 0 ? Metric.of(idle ? "idle_heap_used" : "peak_heap_used", heapPeak, "bytes",
                boundary + ", sampled every 20 ms (lower bound of the true peak)")
                : Metric.unavailable(idle ? "idle_heap_used" : "peak_heap_used", "bytes", boundary, "heap sampling failed"));
        out.add(rssPeak >= 0 ? Metric.of(idle ? "idle_rss" : "peak_rss", rssPeak, "bytes",
                boundary + ", resident set from /proc, sampled every 20 ms")
                : Metric.unavailable(idle ? "idle_rss" : "peak_rss", "bytes", boundary, "/proc/self/status is not available on this platform"));
    }

    private static void collectRepository(List<Metric> out, Recorder rec, long wallNanos) {
        if (rec.repoLatenciesMillis.isEmpty()) {
            return;
        }
        List<Double> lat = new ArrayList<>(rec.repoLatenciesMillis);
        String boundary = "repository call (JDBC, no HTTP), one round = TP quotes + item metadata";
        out.add(Metric.of("repository_read_latency_p95", Stats.p95(lat), "ms", boundary + ", nearest-rank over " + lat.size() + " rounds"));
        out.add(Metric.of("repository_read_throughput", Stats.throughputPerSecond(lat.size(), wallNanos), "rounds/second",
                boundary + ", over the workload wall time"));
        out.add(Metric.of("repository_read_rounds", lat.size(), "rounds", boundary));
    }

    private static void collectHttp(List<Metric> out, Recorder rec, long wallNanos) {
        if (rec.requests.get() == 0) {
            return;
        }
        String boundary = "HTTP client round trip incl. body download";
        if (!rec.latenciesMillis.isEmpty()) {
            List<Double> lat = new ArrayList<>(rec.latenciesMillis);
            out.add(Metric.of("http_latency_p95", Stats.p95(lat), "ms", boundary + ", nearest-rank over " + lat.size() + " requests"));
            out.add(Metric.of("http_throughput", Stats.throughputPerSecond(rec.requests.get(), wallNanos), "requests/second",
                    "all recorded requests (including task-status polls) over wall time"));
        }
        out.add(Metric.of("http_requests", rec.requests.get(), "requests", boundary));
        out.add(Metric.of("http_errors", rec.errors.get(), "requests", "non-2xx responses"));
        out.add(Metric.of("network_ingress", rec.bytesOut.get(), "bytes",
                "HTTP request bodies received by the application; headers and TCP overhead excluded"));
        out.add(Metric.of("network_egress", rec.bytesIn.get(), "bytes",
                "HTTP response bodies sent by the application; headers and TCP overhead excluded"));
    }

    // ================================================================ workload bodies

    private void login() throws Exception {
        HttpResponse<String> r = send("POST", "/api/session", null, "{}", true, new Recorder(), false);
        if (r.statusCode() != 200) throw new IllegalStateException("fixture login failed: " + r.statusCode());
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        cookie = setCookie.substring(0, setCookie.indexOf(';'));
        context = r.headers().firstValue("X-Account-Context").orElseThrow();
    }

    HttpResponse<String> send(String method, String path, Recorder rec) throws Exception {
        return send(method, path, null, null, false, rec, true);
    }

    HttpResponse<String> send(String method, String path, String body, boolean withKey, Recorder rec) throws Exception {
        return send(method, path, null, body, withKey, rec, true);
    }

    private HttpResponse<String> send(String method, String path, Object unused, String body, boolean withKey,
                                      Recorder rec, boolean record) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", "http://localhost:" + port).header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie).header("X-Account-Context", context);
        if (withKey) b.header("X-GW2-API-Key", KEY);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        long t = System.nanoTime();
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (record) {
            rec.latenciesMillis.add(Stats.nanosToMillis(System.nanoTime() - t));
            rec.requests.incrementAndGet();
            if (body != null) rec.bytesOut.addAndGet(body.getBytes(StandardCharsets.UTF_8).length);
            rec.bytesIn.addAndGet(r.body().getBytes(StandardCharsets.UTF_8).length);
            if (r.statusCode() / 100 != 2) {
                rec.errors.incrementAndGet();
                rec.diagnostics.add(method + " " + path + " -> " + r.statusCode());
            }
        }
        return r;
    }

    private void syncAccount(Recorder rec) throws Exception {
        asyncTask(rec, "POST", "/api/sync/account", "{}", true);
    }

    private void asyncTask(Recorder rec, String method, String path, String body) throws Exception {
        asyncTask(rec, method, path, body, false);
    }

    private void asyncTask(Recorder rec, String method, String path, String body, boolean withKey) throws Exception {
        long t = System.nanoTime();
        HttpResponse<String> accepted = send(method, path, body, withKey, rec);
        if (accepted.statusCode() != 202) throw new IllegalStateException(path + " not accepted: " + accepted.statusCode());
        String taskId = JSON.readTree(accepted.body()).path("taskId").asText();
        String state = "";
        for (int i = 0; i < 6000 && !state.equals("SUCCEEDED") && !state.equals("FAILED"); i++) {
            state = JSON.readTree(send("GET", "/api/sync/tasks/" + taskId, rec).body()).path("state").asText();
            if (!state.equals("SUCCEEDED") && !state.equals("FAILED")) Thread.sleep(TASK_POLL_MILLIS);
        }
        rec.jobMillis.add(Stats.nanosToMillis(System.nanoTime() - t));
        if (!state.equals("SUCCEEDED")) throw new IllegalStateException(path + " task ended " + state);
    }

    private void profit(Recorder rec, int repeats) throws Exception {
        // The first call is cold; each repeat changes the buy budget so the cached-result layer cannot answer it.
        for (int i = 0; i <= repeats; i++) {
            String body = "{\"settings\":{\"maxBuyCopper\":" + (10_000 + i) + "}}";
            HttpResponse<String> r = send("POST", "/api/crafting/profit", body, false, rec);
            if (r.statusCode() == 200) {
                JsonNode rows = JSON.readTree(r.body()).path("rows");
                if (i == 0) {
                    int craftable = 0;
                    for (JsonNode row : rows) if (row.path("craftableCount").asInt(0) > 0) craftable++;
                    rec.extra.add(Metric.of("result_rows", rows.size(), "rows", "rows in the first response"));
                    rec.extra.add(Metric.of("craftable_rows", craftable, "rows",
                            "rows of the first response whose calculation crafted at least one item (craftableCount > 0)"));
                    if (craftable == 0) {
                        rec.diagnostics.add("profit calculated " + rows.size() + " rows but none is craftable: the workload did not exercise crafting");
                    }
                }
            } else if (i == 0) {
                throw new IllegalStateException("profit not served: " + r.statusCode() + " " + r.body());
            }
        }
    }

    private void typical(Recorder rec, int rounds) throws Exception {
        for (int i = 0; i < rounds; i++) {
            send("GET", "/api/system/status", rec);
            send("GET", "/api/account/bank", rec);
            send("GET", "/api/account/materials", rec);
            send("GET", "/api/crafting/selector-options", rec);
        }
    }

    private void repositoryReads(Recorder rec, int rounds) throws Exception {
        Set<Integer> ids = new HashSet<>();
        for (int id : allItemIds(active.rawItems(), active.largeRecipes())) ids.add(id);
        TpPriceRepository tp = new TpPriceRepository();
        ItemRepository items = new ItemRepository();
        long cached = 0;
        for (int i = 0; i < rounds; i++) {
            long t = System.nanoTime();
            cached = tp.loadTpQuotes(ids).size() + items.loadItems(ids).size();
            rec.repoLatenciesMillis.add(Stats.nanosToMillis(System.nanoTime() - t));
        }
        rec.extra.add(Metric.of("rows_returned_per_round", cached, "rows", "quotes + items returned by the last round"));
    }

    private void concurrent(Recorder rec, int threads, int perThread) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<Void> client = () -> {
                    for (int i = 0; i < perThread; i++) {
                        switch (i % 4) {
                            case 0 -> send("POST", "/api/crafting/profit", "{}", false, rec);
                            case 1 -> send("GET", "/api/system/status", rec);
                            case 2 -> send("GET", "/api/account/bank", rec);
                            default -> send("GET", "/api/crafting/selector-options", rec);
                        }
                    }
                    return null;
                };
                futures.add(pool.submit(client));
            }
            for (Future<Void> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
    }

    // ================================================================ metadata

    private Map<String, String> environment(Sizes s) throws Exception {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("measurementKind", "process (no container)");
        e.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        e.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        e.put("availableProcessors", Integer.toString(ProcessProbe.processors()));
        e.put("maxHeapBytes", Long.toString(Runtime.getRuntime().maxMemory()));
        e.put("postgresql", db.serverVersion());
        e.put("runner", System.getenv("GITHUB_ACTIONS") != null ? "GitHub Actions" : "local");
        e.put("sizes", s.toString());
        return e;
    }

    static String commit() {
        String sha = System.getenv("GITHUB_SHA");
        if (sha != null && !sha.isBlank()) return sha;
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !out.isBlank()) return out;
        } catch (Exception ignored) {
            // fall through
        }
        return "unknown";
    }
}
