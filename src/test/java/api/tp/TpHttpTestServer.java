package api.tp;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Loopback server replaying scripted responses in order; records request URIs. Never touches ArenaNet. */
final class TpHttpTestServer implements AutoCloseable {
    record Reply(int status, String body, String retryAfter) {}

    private final HttpServer server;
    private final Deque<Reply> script = new ArrayDeque<>();
    final List<String> requests = new ArrayList<>();

    TpHttpTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            Reply r;
            synchronized (this) {
                requests.add(ex.getRequestURI().toString());
                r = script.isEmpty() ? new Reply(500, "script exhausted", null) : script.poll();
            }
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            if (r.retryAfter() != null) ex.getResponseHeaders().add("Retry-After", r.retryAfter());
            ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    TpHttpTestServer reply(int status, String body) { return reply(status, body, null); }

    synchronized TpHttpTestServer reply(int status, String body, String retryAfter) {
        script.add(new Reply(status, body, retryAfter));
        return this;
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    synchronized List<String> requests() { return List.copyOf(requests); }

    @Override public void close() { server.stop(0); }
}
