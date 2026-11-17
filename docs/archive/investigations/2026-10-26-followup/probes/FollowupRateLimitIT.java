package api.tp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FollowupRateLimitIT {
    @Test void observeUnclampedRetryAfterWithoutActuallySleeping()throws Exception {
        var requests=new AtomicInteger();var delays=new ArrayList<Long>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{requests.incrementAndGet();exchange.getResponseHeaders().set("Retry-After","3600");
            byte[] body="rate limited fixture".getBytes();exchange.sendResponseHeaders(429,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.start();
        var oldBase=TpPriceApi.base;var oldSleeper=TpPriceApi.sleeper;
        TpPriceApi.base="http://127.0.0.1:"+server.getAddress().getPort();TpPriceApi.sleeper=delays::add;
        try {
            assertThrows(RuntimeException.class,()->TpPriceApi.fetchBatch(List.of(1)));
            assertEquals(5,requests.get());assertEquals(List.of(3600000L,3600000L,3600000L,3600000L),delays);
            Path out=Path.of("target/followup/rate-limit.json");Files.createDirectories(out.getParent());
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(),
                    Map.of("requests",requests.get(),"requestedSleepMillis",delays,"actualSleepMillis",0,
                            "scope","Loopback 429 fixture, replaced sleeper records intended delays; production retry policy unchanged"));
        } finally { TpPriceApi.base=oldBase;TpPriceApi.sleeper=oldSleeper;server.stop(0); }
    }
}
