package sync;

import com.fasterxml.jackson.databind.JsonNode;

/** Smallest upstream seam for the public GW2 array endpoints used by synchronization; production uses the HTTP client. */
@FunctionalInterface
interface PublicArrayFetcher {
    JsonNode get(String url) throws Exception;
}
