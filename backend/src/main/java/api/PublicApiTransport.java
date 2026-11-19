package api;

import java.io.IOException;

/** Fixed-URL public GET seam for the compatibility check; faked offline, never given user input. */
@FunctionalInterface
public interface PublicApiTransport {

    /** Status and body of one response; the body is parsed by the checker and never retained. */
    record Response(int status, String body) {}

    Response get(String url) throws IOException, InterruptedException;

    /** Production transport over the existing bounded public HTTP client (connect/request timeouts). */
    static PublicApiTransport live() {
        return url -> {
            var res = Gw2ApiClient.getPublicResponse(url);
            return new Response(res.statusCode(), res.body());
        };
    }
}
