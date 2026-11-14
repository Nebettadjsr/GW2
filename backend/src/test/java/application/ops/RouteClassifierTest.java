package application.ops;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteClassifierTest {
    private static Optional<RouteGroup> c(String method, String path) { return RouteClassifier.classify(method, path); }

    @Test
    void includedRoutesMapToFixedGroups() {
        assertEquals(Optional.of(RouteGroup.ACCOUNT_DATA), c("GET", "/api/account/bank"));
        assertEquals(Optional.of(RouteGroup.ACCOUNT_DATA), c("GET", "/api/account/materials"));
        assertEquals(Optional.of(RouteGroup.ACCOUNT_DATA), c("GET", "/api/account/luck"));
        assertEquals(Optional.of(RouteGroup.CHARACTERS), c("GET", "/api/account/characters"));
        assertEquals(Optional.of(RouteGroup.CHARACTERS), c("GET", "/api/account/characters/Some Name"));
        assertEquals(Optional.of(RouteGroup.CHARACTERS), c("GET", "/api/account/characters/x/build-tabs"));
        assertEquals(Optional.of(RouteGroup.INVENTORY), c("GET", "/api/account/characters/Some Name/inventory"));
        assertEquals(Optional.of(RouteGroup.CRAFTING), c("POST", "/api/crafting/profit"));
        assertEquals(Optional.of(RouteGroup.CRAFTING), c("POST", "/api/crafting/profit/resolution"));
        assertEquals(Optional.of(RouteGroup.CRAFTING), c("POST", "/api/crafting/discovery"));
        assertEquals(Optional.of(RouteGroup.CRAFTING), c("GET", "/api/crafting/selector-options"));
        assertEquals(Optional.of(RouteGroup.ACCOUNT_REFRESH), c("POST", "/api/sync/account"));
        assertEquals(Optional.of(RouteGroup.ACCOUNT_REFRESH), c("POST", "/api/sync/account/bank"));
        assertEquals(Optional.of(RouteGroup.CHARACTERS), c("POST", "/api/sync/account/characters"));
    }

    @Test
    void nonForegroundGroups() {
        assertEquals(Optional.of(RouteGroup.SHARED_GAME_DATA), c("GET", "/api/items/123"));
        assertEquals(Optional.of(RouteGroup.SHARED_GAME_DATA), c("GET", "/api/crafting/ingredient-search"));
        assertEquals(Optional.of(RouteGroup.TASK_CONTROL), c("POST", "/api/sync/items"));
        assertEquals(Optional.of(RouteGroup.TASK_CONTROL), c("POST", "/api/prices/refresh"));
        assertEquals(Optional.of(RouteGroup.OTHER_API), c("GET", "/api/something/unknown/42"));
        for (RouteGroup g : RouteGroup.values()) {
            assertEquals(g.ordinal() < 5, g.foreground());
        }
        assertEquals(8, RouteGroup.values().length);
    }

    @Test
    void excludedRoutesAreNotCollected() {
        for (String path : new String[] {"/api/system/status", "/api/system/status/details", "/api/system",
                "/api/sync/tasks/abc", "/api/session", "/api/session/renew", "/api/session/logout",
                "/api/sync/full/items", "/actuator/health", "/health", "/assets/app.js", "/", "/index.html"}) {
            assertEquals(Optional.empty(), c("GET", path), path);
        }
        assertEquals(Optional.empty(), c("POST", "/api/session/logout"));
        assertEquals(Optional.empty(), c("OPTIONS", "/api/account/bank"));
        assertEquals(Optional.empty(), c("HEAD", "/api/account/bank"));
    }

    @Test
    void explicitTaskMutationIsTaskControlButPollingIsNot() {
        assertEquals(Optional.of(RouteGroup.TASK_CONTROL), c("DELETE", "/api/sync/tasks/abc"));
        assertEquals(Optional.empty(), c("GET", "/api/sync/tasks/abc"));
    }
}
