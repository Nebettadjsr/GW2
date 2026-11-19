package api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed-URL public GET seam for the compatibility check; faked offline, never given user input. */
@FunctionalInterface
public interface PublicApiTransport {

    /** Largest response body the check will accumulate; more is cancelled while still being read. */
    int MAX_BODY_BYTES = 1_048_576;
    /** Wall-clock bound for one request including the body read (headers alone are not enough). */
    Duration REQUEST_BOUND = TaskDeadline.REQUEST_TIMEOUT;

    /**
     * Status and body of one response; the body is parsed by the checker and never retained.
     * {@code body} is {@code null} when the response exceeded the byte bound and was cancelled.
     */
    record Response(int status, String body) {}

    Response get(String url) throws IOException, InterruptedException;

    /** Production transport: bounded bytes while reading, finite connect/request/body-read time. */
    static PublicApiTransport live() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TaskDeadline.CONNECT_TIMEOUT).build();
        return bounded(client, MAX_BODY_BYTES, REQUEST_BOUND);
    }

    /**
     * Transport that stops reading once {@code maxBytes} arrive (cancelling the stream) and abandons the
     * request when {@code bound} elapses, even if the server stalls after sending headers.
     */
    static PublicApiTransport bounded(HttpClient client, int maxBytes, Duration bound) {
        return url -> {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Accept", "application/json").timeout(bound).GET().build();
            CompletableFuture<HttpResponse<byte[]>> future =
                    client.sendAsync(req, info -> new LimitedBody(maxBytes));
            try {
                HttpResponse<byte[]> res = future.get(bound.toMillis(), TimeUnit.MILLISECONDS);
                byte[] body = res.body();
                return new Response(res.statusCode(), body == null ? null : new String(body, StandardCharsets.UTF_8));
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new HttpTimeoutException("response was not complete within the bound");
            } catch (InterruptedException e) {
                future.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                future.cancel(true);
                if (e.getCause() instanceof IOException io) throw io;
                throw new IOException("request failed");
            }
        };
    }

    /** Accumulates at most {@code max} bytes; on excess cancels the upstream and completes with null. */
    final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean done;

        LimitedBody(int max) { this.max = max; }

        @Override public CompletableFuture<byte[]> getBody() { return result; }

        @Override public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(List<ByteBuffer> items) {
            if (done) return;
            for (ByteBuffer b : items) {
                if (out.size() + b.remaining() > max) {
                    done = true;
                    subscription.cancel();
                    result.complete(null);
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override public void onError(Throwable t) { done = true; result.completeExceptionally(t); }

        @Override public void onComplete() { if (!done) { done = true; result.complete(out.toByteArray()); } }
    }
}
