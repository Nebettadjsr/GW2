package sync;

import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.mock.web.MockHttpServletRequest;
import repo.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fault probes against loopback only; production code and normal database are untouched. */
class FollowupReliabilityIT {
    final ObjectMapper json = new ObjectMapper();
    final Map<String,Object> report = new LinkedHashMap<>();

    @Test void measureReliabilityBoundaries() throws Exception {
        slowHttp();
        heldConnection();
        proxyOrigin();
        Path out=Path.of("target/followup/reliability.json");
        Files.createDirectories(out.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),report);
    }

    void slowHttp() throws Exception {
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/slow",exchange->{
            entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); }
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            byte[] body="[]".getBytes();
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            long start=System.nanoTime();
            var pending=executor.submit(()->Gw2ApiClient.getPublic("http://127.0.0.1:"+server.getAddress().getPort()+"/slow"));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->pending.get(1500,TimeUnit.MILLISECONDS));
            report.put("slowHttp",Map.of("stillWaitingAfterMs",(System.nanoTime()-start)/1e6,"cancelledByClientTimeout",false,
                    "scope","Injected loopback stalled response; external 1500ms observer budget, no production timeout claimed"));
            release.countDown();
            assertTrue(pending.get(3,TimeUnit.SECONDS).isArray());
        } finally { release.countDown();server.stop(0); }
    }

    void heldConnection() throws Exception {
        String schema="followup_tp_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(AppConfig.DB_URL,AppConfig.DB_USER,AppConfig.DB_PASS)) {
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            try {
                Flyway.configure().dataSource(AppConfig.DB_URL,AppConfig.DB_USER,AppConfig.DB_PASS)
                        .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
                admin.setSchema(schema);
                admin.createStatement().execute("INSERT INTO items(item_id) VALUES(1)");
                System.setProperty(Db.TEST_SCHEMA_PROPERTY,schema);
                var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
                var original=TpSync.upstream;
                TpSync.upstream=new TpSync.TpUpstream(){
                    public api.tp.TpPriceApi.BatchResult fetchBatch(List<Integer> ids)throws Exception {
                        entered.countDown();release.await(5,TimeUnit.SECONDS);
                        return new api.tp.TpPriceApi.BatchResult(200,json.readTree("[{\"id\":1,\"buys\":{\"unit_price\":10,\"quantity\":50},\"sells\":{\"unit_price\":20,\"quantity\":50}}]"),Set.of(1));
                    }
                    public com.fasterxml.jackson.databind.JsonNode fetchSingle(int id){throw new AssertionError();}
                    public Set<Integer> fetchAllTradeableItemIds(){throw new AssertionError();}
                };
                try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                    var pending=executor.submit(()->{TpSync.syncTpPrices(Set.of(1));return null;});
                    assertTrue(entered.await(3,TimeUnit.SECONDS));
                    var field=Db.class.getDeclaredField("connections");field.setAccessible(true);
                    Object connections=field.get(null);
                    var poolField=connections.getClass().getDeclaredField("pool");poolField.setAccessible(true);
                    var pool=(com.zaxxer.hikari.HikariDataSource)poolField.get(connections);
                    int held=pool.getHikariPoolMXBean().getActiveConnections();
                    assertEquals(1,held);
                    release.countDown();pending.get(5,TimeUnit.SECONDS);
                    assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
                    report.put("tpConnection",Map.of("leasesHeldWhileUpstreamBlocked",held,"leasesAfterCompletion",0,"configuredPoolSize",pool.getMaximumPoolSize()));
                } finally { release.countDown();TpSync.upstream=original; }
            } finally { System.clearProperty(Db.TEST_SCHEMA_PROPERTY);admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }

    void proxyOrigin() {
        var request=new MockHttpServletRequest();
        request.setScheme("http");request.setServerName("gw2.example");request.setServerPort(80);
        request.addHeader("Origin","https://gw2.example");
        var defaults=new web.access.AccountAccessHttp("");
        var configured=new web.access.AccountAccessHttp("https://gw2.example");
        assertFalse(defaults.isSameOrigin(request));
        assertTrue(configured.isSameOrigin(request));
        assertTrue(defaults.sessionCookie(request,"fixture").contains("Secure"));
        report.put("httpsProxy",Map.of("defaultHttpHopAcceptsExternalHttpsOrigin",false,
                "explicitAllowedPublicOriginAccepts",true,"nonLoopbackCookieIsSecure",true,
                "boundary","Servlet origin/cookie contract, not a deployed TLS browser test"));
    }
}
