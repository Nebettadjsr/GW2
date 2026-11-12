package perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Owns the {@code frontend/scripts/capacity-probe.mjs} child process: the unchanged built frontend on one
 * loopback origin proxying {@code /api} to the fixture application, plus sequential real-Chromium probes.
 * Requests are strictly one at a time; {@link #close()} stops the browser and the process.
 */
final class BrowserProbe implements AutoCloseable {
    private static final ObjectMapper M = new ObjectMapper();
    private final Process process;
    private final BufferedWriter in;
    private final BlockingQueue<JsonNode> lines = new LinkedBlockingQueue<>();
    private final String origin;
    private int nextId;

    BrowserProbe(Path frontendDir, String backendUrl) throws Exception {
        if (!java.nio.file.Files.isDirectory(frontendDir.resolve("dist"))) {
            throw new IllegalStateException("frontend/dist is missing: run `npm run build` in frontend/ first");
        }
        ProcessBuilder pb = new ProcessBuilder("node", "scripts/capacity-probe.mjs", "--backend", backendUrl, "--dist", "dist")
                .directory(frontendDir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        process = pb.start();
        in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("{")) lines.add(M.readTree(l));
                }
            } catch (Exception ignored) { /* process ended */ }
        }, "capacity-probe-reader");
        reader.setDaemon(true);
        reader.start();
        JsonNode ready = lines.poll(60, TimeUnit.SECONDS);
        if (ready == null || !ready.path("ready").asBoolean()) {
            close();
            throw new IllegalStateException("browser probe did not start");
        }
        origin = ready.path("origin").asText();
    }

    String origin() { return origin; }

    synchronized JsonNode call(ObjectNode cmd, long timeoutMillis) throws Exception {
        int id = ++nextId;
        cmd.put("id", id);
        in.write(cmd.toString());
        in.newLine();
        in.flush();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            JsonNode r = lines.poll(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
            if (r == null) throw new java.util.concurrent.TimeoutException("browser probe did not answer");
            if (r.path("id").asInt() == id) return r;
        }
    }

    /** Hosts of every non-loopback browser request that the probe aborted (empty when none was attempted). */
    java.util.List<String> blockedRequests() throws Exception {
        JsonNode r = call(M.createObjectNode().put("cmd", "blocked"), 15_000);
        java.util.List<String> out = new java.util.ArrayList<>();
        r.path("blocked").forEach(n -> out.add(n.asText()));
        return out;
    }

    /** Proof hook: the logged-in page tries to load {@code url}; returns whether it loaded and how many requests were blocked. */
    JsonNode attempt(String principal, String url) throws Exception {
        return call(M.createObjectNode().put("cmd", "attempt").put("principal", principal).put("url", url), 30_000);
    }

    /** Real-browser warm-cache observation: reloads the logged-in page and reports assets loaded vs. assets that reached the server. */
    JsonNode cacheCheck(String principal) throws Exception {
        return call(M.createObjectNode().put("cmd", "cachecheck").put("principal", principal), 60_000);
    }

    boolean isAlive() { return process.isAlive(); }

    @Override
    public void close() {
        try {
            in.write("{\"cmd\":\"close\"}\n");
            in.flush();
        } catch (Exception ignored) { /* already gone */ }
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
