package perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Concrete {@link StageRunner}: an open-loop, seeded protocol generator (one independent HTTP session and
 * account partition per virtual user) plus a small sequential browser probe against the SAME application.
 * Of the configured level L, {@code min(probeBudget, L)} users are browser-probe principals (counted in
 * the disclosed total, never added silently) and the rest are protocol users. Local-only actions generate
 * no requests. Browser usable-completion decides capacity; protocol timings are retained separately.
 */
final class CapacityStageRunner implements StageRunner {
    static final String PROFIT_BODY = "{\"scope\":{\"kind\":\"DISCIPLINE\",\"discipline\":\"Chef\"},"
            + "\"settings\":{\"useOwnMats\":true,\"allowBuying\":false,\"maxBuyCopper\":1000000}}";
    private static final ObjectMapper M = new ObjectMapper();
    private static final long LATE_MS = 250;

    private final CapacityConfig cfg;
    private final String baseUrl;
    private final IntFunction<String> keyOf;
    private final BrowserProbe probe;
    private final ExecutorService httpExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "capacity-http");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().executor(httpExecutor).build();
    /** True while a browser action is in flight; the browser must not be destroyed then. */
    private final java.util.concurrent.atomic.AtomicInteger browserInFlight = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean workersRetained;
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final Set<Integer> browserLoggedIn = new HashSet<>();
    private final Map<String, String> baselinePrints = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "capacity-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicStop stop = new AtomicStop();
    /** Per-stage admission stop: set on a correctness failure or at window end; reset by each run. */
    private volatile boolean stageStop;
    private final List<Future<?>> outstanding = new CopyOnWriteArrayList<>();
    private final Supplier<Boolean> applicationDrained;
    private final Map<String, Integer> expectedBank = new ConcurrentHashMap<>();

    private static final class AtomicStop { volatile boolean stopped; }

    private record Session(String cookie, String context) { }

    CapacityStageRunner(CapacityConfig cfg, String baseUrl, IntFunction<String> keyOf, BrowserProbe probe,
                        Supplier<Boolean> applicationDrained) {
        this.cfg = cfg;
        this.baseUrl = baseUrl;
        this.keyOf = keyOf;
        this.probe = probe;
        this.applicationDrained = applicationDrained;
    }

    // ---- protocol -------------------------------------------------------------------------------------------

    private HttpResponse<String> raw(String method, String path, String body, String key, Session s) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(java.time.Duration.ofSeconds(60))
                .header("Origin", baseUrl).header("Content-Type", "application/json");
        if (key != null) b.header("X-GW2-API-Key", key);
        if (s != null) b.header("Cookie", s.cookie()).header("X-Account-Context", s.context());
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Session session(int user) throws Exception {
        Session s = sessions.get(user);
        if (s != null) return s;
        HttpResponse<String> r = raw("POST", "/api/session", "{}", keyOf.apply(user), null);
        if (r.statusCode() != 200) throw new IllegalStateException("fixture login failed for user " + user + ": HTTP " + r.statusCode());
        String set = r.headers().firstValue("Set-Cookie").orElseThrow();
        s = new Session(set.substring(0, set.indexOf(';')), r.headers().firstValue("X-Account-Context").orElseThrow());
        sessions.put(user, s);
        return s;
    }

    private record Outcome(boolean usable, boolean correct, String print) { }

    private Outcome protocolJourney(int user, String journey) throws Exception {
        Session s = session(user);
        switch (journey) {
            case "bank": {
                HttpResponse<String> r = raw("GET", "/api/account/bank", null, null, s);
                return outcome(r, "bank:" + r.body());
            }
            case "materials": {
                HttpResponse<String> r = raw("GET", "/api/account/materials", null, null, s);
                return outcome(r, "materials:" + r.body());
            }
            case "characters": {
                HttpResponse<String> r = raw("GET", "/api/account/characters", null, null, s);
                return new Outcome(r.statusCode() == 200, r.statusCode() == 200, "characters:" + rosterNames(r).size());
            }
            case "character-core": {
                String name = firstCharacter(s);
                String enc = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                HttpResponse<String> a = raw("GET", "/api/account/characters/" + enc, null, null, s);
                HttpResponse<String> b = raw("GET", "/api/account/characters/" + enc + "/equipment-tabs", null, null, s);
                HttpResponse<String> c = raw("GET", "/api/account/characters/" + enc + "/build-tabs", null, null, s);
                boolean ok = a.statusCode() == 200 && b.statusCode() == 200 && c.statusCode() == 200;
                return new Outcome(ok, ok, null);
            }
            case "character-inventory": {
                String enc = URLEncoder.encode(firstCharacter(s), StandardCharsets.UTF_8).replace("+", "%20");
                HttpResponse<String> r = raw("GET", "/api/account/characters/" + enc + "/inventory", null, null, s);
                return new Outcome(r.statusCode() == 200, r.statusCode() == 200, null);
            }
            case "crafting-profit": {
                HttpResponse<String> r = raw("POST", "/api/crafting/profit", PROFIT_BODY, null, s);
                return new Outcome(r.statusCode() == 200, r.statusCode() == 200, r.statusCode() == 200 ? "profit:" + profitPrint(M.readTree(r.body())) : null);
            }
            case "recipe-detail": {
                HttpResponse<String> p = raw("POST", "/api/crafting/profit", PROFIT_BODY, null, s);
                JsonNode root = M.readTree(p.body());
                JsonNode first = firstRow(root);
                if (p.statusCode() != 200 || first == null) return new Outcome(false, false, null);
                ObjectNode req = M.createObjectNode();
                req.put("recipeId", first.path("recipeId").asInt());
                req.set("calculation", M.readTree(PROFIT_BODY));
                if (root.hasNonNull("calculationId")) req.put("basedOnCalculationId", root.get("calculationId").asText());
                HttpResponse<String> r = raw("POST", "/api/crafting/profit/resolution", req.toString(), null, s);
                return new Outcome(r.statusCode() == 200, r.statusCode() == 200, null);
            }
            case "system-status": {
                HttpResponse<String> r = raw("GET", "/api/system/status", null, null, s);
                return new Outcome(r.statusCode() == 200, r.statusCode() == 200, null);
            }
            default:
                throw new IllegalArgumentException("unknown journey " + journey);
        }
    }

    private static Outcome outcome(HttpResponse<String> r, String print) {
        return new Outcome(r.statusCode() == 200, r.statusCode() == 200, print);
    }

    private static JsonNode firstRow(JsonNode profit) {
        for (String f : List.of("rows", "results", "opportunities")) {
            if (profit.path(f).isArray() && profit.path(f).size() > 0) return profit.path(f).get(0);
        }
        return null;
    }

    private static String profitPrint(JsonNode profit) {
        List<String> rows = new ArrayList<>();
        for (String f : List.of("rows", "results", "opportunities")) {
            for (JsonNode n : profit.path(f)) rows.add(n.path("recipeId").asText() + ":" + n.path("craftableCount").asText());
        }
        Collections.sort(rows);
        return String.join(",", rows);
    }

    private List<String> rosterNames(HttpResponse<String> r) throws Exception {
        List<String> names = new ArrayList<>();
        JsonNode root = M.readTree(r.body());
        JsonNode arr = root.isArray() ? root : root.path("characters");
        for (JsonNode n : arr) names.add(n.isTextual() ? n.asText() : n.path("name").asText());
        return names;
    }

    private String firstCharacter(Session s) throws Exception {
        List<String> names = rosterNames(raw("GET", "/api/account/characters", null, null, s));
        if (names.isEmpty()) throw new IllegalStateException("fixture principal has no character");
        return names.get(0);
    }

    private static String fingerprint(String s) {
        return s == null ? null : CapacityReport.sha256(s);
    }

    // ---- stages ---------------------------------------------------------------------------------------------

    @Override
    public StageObservation run(int level, String role, long seed) {
        long begin = System.nanoTime();
        List<CapacitySample> samples = new CopyOnWriteArrayList<>();
        String invalid = null;
        int scheduled = 0, achieved = 0, late = 0, missed = 0;
        boolean saturated = false;
        stageStop = false;
        List<Future<?>> stageFutures = new CopyOnWriteArrayList<>();
        boolean serial = role.equals(StageObservation.BASELINE) || role.equals(StageObservation.WARMUP);
        try {
            int browserUsers = Math.min(cfg.probeBudget(), level);
            int protocolUsers = level - browserUsers;
            for (int b = 0; b < browserUsers; b++) ensureBrowser(b);
            for (int u = 0; u < level; u++) session(u);
            if (serial) {
                for (String j : cfg.journeys()) {
                    protocolSample(samples, 0, j, role, level, 0, 0, serial);
                    browserSample(samples, 0, j, role, level, 0);
                }
                scheduled = achieved = cfg.journeys().size();
            } else {
                List<WorkloadProfile.ScheduledStart> plan = protocolUsers == 0 ? List.of()
                        : cfg.profile().plan(protocolUsers, seed, cfg.observationWindowMillis(), cfg.journeys());
                scheduled = plan.size();
                long t0 = System.nanoTime();
                int[] counters = new int[3]; // achieved, late, missed
                stageFutures.add(workers.submit(() -> browserCycle(samples, browserUsers, role, level, t0)));
                for (WorkloadProfile.ScheduledStart st : plan) {
                    if (!sleepUntil(t0, st.offsetMillis())) { counters[2]++; continue; }
                    if (stop.stopped || stageStop) { counters[2]++; continue; }
                    long delay = Math.max(0, (System.nanoTime() - t0) / 1_000_000 - st.offsetMillis());
                    counters[0]++;
                    if (delay > LATE_MS) counters[1]++;
                    int principal = browserUsers + st.user();
                    stageFutures.add(workers.submit(() -> {
                        try { protocolSample(samples, principal, st.journey(), role, level, st.offsetMillis(), delay, false); }
                        catch (Exception e) { samples.add(new CapacitySample(st.journey(), CapacitySample.PROTOCOL, level, role, principal, st.offsetMillis(), delay, 0, false, false)); stageStop = true; }
                    }));
                }
                sleepUntil(t0, cfg.observationWindowMillis());
                achieved = counters[0]; late = counters[1]; missed = counters[2];
                saturated = scheduled > 0 && (missed > 0 && !stageStop || late > Math.max(1, scheduled / 20));
            }
        } catch (Exception e) {
            stageStop = true;
            invalid = "stage failed: " + e.getClass().getSimpleName();
        } finally {
            stageStop = true; // stop admission, then bound-wait for ALL stage workers before freezing samples
            if (!awaitAll(stageFutures, cfg.drainTimeoutMillis())) {
                stageFutures.stream().filter(f -> !f.isDone()).forEach(outstanding::add);
                workersRetained = true;
                if (invalid == null) invalid = "stage workers did not drain within the bound; resources retained";
            }
        }
        return new StageObservation(level, role, seed, new ArrayList<>(samples), scheduled, achieved, late, missed, saturated, invalid,
                (System.nanoTime() - begin) / 1_000_000);
    }

    /** Sleeps until offset ms after t0, waking early (false) when admission is stopped. */
    private boolean sleepUntil(long t0, long offsetMillis) {
        while (true) {
            if (stop.stopped || stageStop) return false;
            long wait = offsetMillis - (System.nanoTime() - t0) / 1_000_000;
            if (wait <= 0) return true;
            try { Thread.sleep(Math.min(wait, 50)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); stageStop = true; return false; }
        }
    }

    private static boolean awaitAll(List<Future<?>> fs, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Future<?> f : fs) {
            try { f.get(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS); }
            catch (TimeoutException e) { return false; }
            catch (Exception ignored) { /* a failed worker is no longer running */ }
        }
        return true;
    }

    private void ensureBrowser(int user) throws Exception {
        if (browserLoggedIn.contains(user)) return;
        ObjectNode c = M.createObjectNode();
        c.put("cmd", "login").put("principal", "p" + user).put("key", keyOf.apply(user));
        JsonNode r = probe.call(c, 60_000);
        if (!r.path("ok").asBoolean()) throw new IllegalStateException("browser login failed: " + r.path("error").asText());
        browserLoggedIn.add(user);
    }

    private void protocolSample(List<CapacitySample> out, int user, String journey, String role, int level,
                                long offset, long delay, boolean recordPrint) throws Exception {
        long t = System.nanoTime();
        Outcome o;
        try { o = protocolJourney(user, journey); }
        catch (Exception e) { o = new Outcome(false, false, null); }
        long ms = (System.nanoTime() - t) / 1_000_000;
        boolean correct = o.correct();
        String print = fingerprint(o.print());
        if (print != null) {
            if (role.equals(StageObservation.BASELINE)) baselinePrints.putIfAbsent(journey, print);
            else if (baselinePrints.containsKey(journey) && !baselinePrints.get(journey).equals(print)) correct = false;
        }
        out.add(new CapacitySample(journey, CapacitySample.PROTOCOL, level, role, user, offset, delay, ms, o.usable(), correct));
        if (!correct) stageStop = true;
    }

    private void browserCycle(List<CapacitySample> out, int browserUsers, String role, int level, long t0) {
        if (browserUsers == 0) return;
        List<String> js = cfg.journeys();
        long end = t0 + TimeUnit.MILLISECONDS.toNanos(cfg.observationWindowMillis());
        int i = 0, covered = 0;
        Random r = new Random(level * 31L + js.size());
        int start = r.nextInt(js.size());
        // Cycle until the window ends AND every enabled journey has at least one browser sample.
        while (!stop.stopped && !stageStop && (System.nanoTime() < end || covered < js.size())) {
            String j = js.get((start + i) % js.size());
            long offset = (System.nanoTime() - t0) / 1_000_000;
            try { browserSample(out, i % browserUsers, j, role, level, offset); }
            catch (Exception e) { out.add(new CapacitySample(j, CapacitySample.BROWSER, level, role, i % browserUsers, offset, 0, 0, false, false)); }
            i++;
            covered = Math.min(js.size(), covered + 1);
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void browserSample(List<CapacitySample> out, int user, String journey, String role, int level, long offset) throws Exception {
        ObjectNode c = M.createObjectNode();
        c.put("cmd", "probe").put("principal", "p" + user).put("journey", journey);
        if (journey.equals("bank")) {
            Integer n = expectedBank.get("p" + user);
            if (n == null) {
                JsonNode bank = M.readTree(raw("GET", "/api/account/bank", null, null, session(user)).body());
                n = bank.path("slots").size();
                expectedBank.put("p" + user, n);
            }
            c.put("expectCount", n);
        }
        JsonNode r;
        browserInFlight.incrementAndGet();
        try { r = probe.call(c, 90_000); } finally { browserInFlight.decrementAndGet(); }
        boolean ok = r.path("ok").asBoolean();
        if (!ok) System.err.println("browser probe " + journey + " failed: " + r.path("error").asText());
        else if (!r.path("correct").asBoolean() || !r.path("usable").asBoolean()) System.err.println("browser probe " + journey + ": usable=" + r.path("usable").asBoolean() + " " + r.path("detail").asText());
        CapacitySample smp = new CapacitySample(journey, CapacitySample.BROWSER, level, role, user, offset, 0,
                ok ? r.path("durationMillis").asLong() : 0, ok && r.path("usable").asBoolean(), ok && r.path("correct").asBoolean());
        out.add(smp);
        if (!smp.correct()) stageStop = true;
    }

    @Override
    public void stopScheduling() { stop.stopped = true; }

    @Override
    public boolean drain(long timeoutMillis) {
        stop.stopped = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Future<?> f : outstanding) {
            long left = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            try { f.get(left, TimeUnit.MILLISECONDS); }
            catch (TimeoutException e) { return false; }
            catch (Exception ignored) { /* a failed worker is no longer running */ }
        }
        while (!applicationDrained.get()) {
            if (System.nanoTime() > deadline) return false;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    /** True when workers or a browser action may still be running: keep the application, schema and browser. */
    boolean resourcesRetained() { return workersRetained || browserInFlight.get() > 0; }

    /** Releases generator-owned resources. Returns false (and keeps them) if anything is still in flight. */
    boolean shutdown() {
        stop.stopped = true;
        stageStop = true;
        if (resourcesRetained()) return false;
        workers.shutdownNow();
        httpExecutor.shutdownNow();
        return true;
    }
}
