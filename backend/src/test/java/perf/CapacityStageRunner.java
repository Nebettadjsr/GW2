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
    static final String PROFIT_BODY = "{\"scope\":{\"kind\":\"ALL\"},"
            + "\"settings\":{\"useOwnMats\":true,\"allowBuying\":false,\"maxBuyCopper\":1000000}}";
    private static final ObjectMapper M = new ObjectMapper();
    private static final int MAX_WORKERS = 256;

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
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final Set<Integer> browserLoggedIn = new HashSet<>();
    private final Map<String, String> baselinePrints = new ConcurrentHashMap<>();
    /** Bounded: a start the pool cannot take is counted as missed (generator saturation), never queued unboundedly. */
    private final ExecutorService workers = new ThreadPoolExecutor(0, MAX_WORKERS, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
        Thread t = new Thread(r, "capacity-worker");
        t.setDaemon(true);
        return t;
    });
    private final RetainedWorkers retainedWorkers = new RetainedWorkers();
    private volatile StartLedger ledger = new StartLedger(() -> 0);
    /** Position on each user's continuous seeded timeline; advances by one window per measured stage. */
    private long workloadClockMillis;
    private final AtomicStop stop = new AtomicStop();
    /** Per-stage admission stop: set on a correctness failure or at window end; reset by each run. */
    private volatile boolean stageStop;
    private final Supplier<Boolean> applicationDrained;
    private final Map<String, Integer> expectedBank = new ConcurrentHashMap<>();

    private static final class AtomicStop { volatile boolean stopped; }

    private static final class Session {
        final String cookie, context;
        /** The profit result this actor's Crafting screen currently shows (recipe detail resolves one of its rows). */
        volatile JsonNode lastProfit;
        Session(String cookie, String context) { this.cookie = cookie; this.context = context; }
        String cookie() { return cookie; }
        String context() { return context; }
    }

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
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        ledger.httpCall(resp.statusCode());
        return resp;
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
                if (r.statusCode() != 200) return new Outcome(false, false, null);
                JsonNode root = M.readTree(r.body());
                s.lastProfit = root;
                return new Outcome(true, true, "profit:" + profitPrint(root));
            }
            case "recipe-detail": {
                JsonNode root = s.lastProfit;
                if (root == null) { // no calculation on screen yet: the actor must calculate first (counted as real calls)
                    HttpResponse<String> p = raw("POST", "/api/crafting/profit", PROFIT_BODY, null, s);
                    if (p.statusCode() != 200) return new Outcome(false, false, null);
                    root = M.readTree(p.body());
                    s.lastProfit = root;
                }
                JsonNode first = firstRow(root);
                if (first == null) return new Outcome(false, false, null);
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
        long[] t0Holder = {System.nanoTime()};
        StartLedger led = new StartLedger(() -> (System.nanoTime() - t0Holder[0]) / 1_000_000);
        ledger = led;
        List<CapacitySample> samples = new CopyOnWriteArrayList<>();
        String invalid = null;
        stageStop = false;
        List<Future<?>> stageFutures = new CopyOnWriteArrayList<>();
        boolean serial = role.equals(StageObservation.BASELINE) || role.equals(StageObservation.WARMUP);
        int scheduledSerial = 0;
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
                scheduledSerial = cfg.journeys().size();
            } else {
                // The plan is a slice of each user's continuous timeline; the workload clock advances per measured window.
                long windowStart = workloadClockMillis;
                workloadClockMillis += cfg.observationWindowMillis();
                List<WorkloadProfile.ScheduledStart> plan = protocolUsers == 0 ? List.of()
                        : cfg.profile().plan(protocolUsers, cfg.seed(), windowStart, cfg.observationWindowMillis(), cfg.journeys(),
                                cfg.craftBurstMin(), cfg.craftBurstMax());
                t0Holder[0] = System.nanoTime();
                long t0 = t0Holder[0];
                stageFutures.add(workers.submit(() -> sparseProbes(samples, browserUsers, role, level, t0, seed)));
                for (WorkloadProfile.ScheduledStart st : plan) {
                    led.offered(st.poll());
                    if (!sleepUntil(t0, st.offsetMillis()) || stop.stopped || stageStop) { led.missed(st.poll()); continue; }
                    int principal = browserUsers + st.user();
                    try {
                        stageFutures.add(workers.submit(() -> {
                            // queue delay is measured HERE, when the worker really begins, not at submission
                            long delay = led.started(st.poll(), st.offsetMillis());
                            boolean ok = false;
                            try {
                                ok = runProtocolStart(samples, principal, st, role, level, delay);
                            } catch (Exception e) {
                                samples.add(new CapacitySample(st.journey(), CapacitySample.PROTOCOL, level, role, principal, st.offsetMillis(), delay, 0, false, false));
                                stageStop = true;
                            } finally {
                                led.finished(st.poll(), ok);
                            }
                        }));
                        led.submitted(st.poll());
                    } catch (RejectedExecutionException e) {
                        led.missed(st.poll()); // the bounded pool could not deliver the offered start: generator saturation
                    }
                }
                sleepUntil(t0, cfg.observationWindowMillis());
            }
        } catch (Exception e) {
            stageStop = true;
            invalid = "stage failed: " + e.getClass().getSimpleName();
        } finally {
            stageStop = true; // stop admission, then bound-wait for ALL stage workers before freezing samples
            if (!awaitAll(stageFutures, cfg.drainTimeoutMillis())) {
                retainedWorkers.retain(stageFutures);
                if (invalid == null) invalid = "stage workers did not drain within the bound; resources retained";
            }
        }
        StartAccounting acc = led.snapshot();
        boolean saturated = !serial && (led.saturated() || acc.journeys().pending() > 0 || acc.polls().pending() > 0);
        int offered = serial ? scheduledSerial : acc.journeys().offered() + acc.polls().offered();
        int started = serial ? scheduledSerial : acc.journeys().started() + acc.polls().started();
        int late = acc.journeys().late() + acc.polls().late();
        int missed = acc.journeys().missed() + acc.polls().missed();
        return new StageObservation(level, role, seed, new ArrayList<>(samples), offered, started, late, missed, saturated, invalid,
                (System.nanoTime() - begin) / 1_000_000, acc);
    }

    private boolean runProtocolStart(List<CapacitySample> samples, int principal, WorkloadProfile.ScheduledStart st,
                                     String role, int level, long delay) throws Exception {
        if (!st.poll()) {
            return protocolSample(samples, principal, st.journey(), role, level, st.offsetMillis(), delay, false);
        }
        // A mounted Status page's ordinary poll: same read as the journey, retained as a poll (not a journey sample).
        HttpResponse<String> r = raw("GET", "/api/system/status", null, null, session(principal));
        boolean ok = r.statusCode() == 200;
        if (!ok) {
            samples.add(new CapacitySample(st.journey(), CapacitySample.PROTOCOL, level, role, principal, st.offsetMillis(), delay, 0, false, false));
            stageStop = true;
        }
        return ok;
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

    private boolean protocolSample(List<CapacitySample> out, int user, String journey, String role, int level,
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
        return correct && o.usable();
    }

    /**
     * Sparse, sequential browser probes: {@code probeSamplesPerJourney} sample(s) of EVERY enabled journey per
     * measured stage, spread across the window in a seeded order (not an endless 1 s cycle). Each is counted
     * inside L by the probe principal(s); the probe contribution is reported via the accounting.
     */
    private void sparseProbes(List<CapacitySample> out, int browserUsers, String role, int level, long t0, long seed) {
        if (browserUsers == 0) return;
        List<String> order = new ArrayList<>();
        for (int k = 0; k < cfg.probeSamplesPerJourney(); k++) {
            List<String> js = new ArrayList<>(cfg.journeys());
            Collections.shuffle(js, new Random(seed * 31L + level * 7L + k));
            order.addAll(js);
        }
        int n = order.size();
        for (int i = 0; i < n && !stop.stopped && !stageStop; i++) {
            long target = (long) ((i + 0.5) * cfg.observationWindowMillis() / n);
            if (!sleepUntil(t0, target)) return;
            String j = order.get(i);
            long offset = (System.nanoTime() - t0) / 1_000_000;
            try { browserSample(out, i % browserUsers, j, role, level, offset); }
            catch (Exception e) { out.add(new CapacitySample(j, CapacitySample.BROWSER, level, role, i % browserUsers, offset, 0, 0, false, false)); }
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
        ledger.browserCall(r.path("httpCalls").asInt(), r.path("polls").asInt());
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
        for (Future<?> f : retainedWorkers.snapshot()) {
            long left = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            try { f.get(left, TimeUnit.MILLISECONDS); }
            catch (TimeoutException e) { return false; }
            catch (Exception ignored) { /* a failed worker is no longer running */ }
        }
        // Retained futures are finished now: forget them so cleanupRequired reflects reality.
        retainedWorkers.reconcile();
        while (!applicationDrained.get()) {
            if (System.nanoTime() > deadline) return false;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    /** True when workers or a browser action may still be running: keep the application, schema and browser. */
    boolean resourcesRetained() { return retainedWorkers.any() || browserInFlight.get() > 0; }

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
