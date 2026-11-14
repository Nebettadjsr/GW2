package application.ops;

import java.util.Optional;

/**
 * Fixed-cardinality route classification (STORY-OPS-001 sections 2-3). Takes only the HTTP method and a
 * path with no query string; dynamic segments never become labels. Empty means "not collected": system,
 * owner/admin, session, task polling, health, assets and every non-API route.
 */
public final class RouteClassifier {
    private RouteClassifier() {}

    public static Optional<RouteGroup> classify(String method, String path) {
        if (method == null || path == null) return Optional.empty();
        if (method.equals("OPTIONS") || method.equals("HEAD") || method.equals("TRACE")) return Optional.empty();
        if (!path.startsWith("/api/")) return Optional.empty();
        String p = path.length() > 5 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (under(p, "/api/system") || under(p, "/api/session") || under(p, "/api/sync/full")) return Optional.empty();
        if (under(p, "/api/sync/tasks")) {
            // Observing task state is polling; only an explicit mutation (admission/control) is collected.
            return method.equals("GET") ? Optional.empty() : Optional.of(RouteGroup.TASK_CONTROL);
        }
        if (under(p, "/api/account")) {
            if (p.equals("/api/account/characters") || p.startsWith("/api/account/characters/")) {
                return Optional.of(p.endsWith("/inventory") ? RouteGroup.INVENTORY : RouteGroup.CHARACTERS);
            }
            return Optional.of(RouteGroup.ACCOUNT_DATA);
        }
        if (p.equals("/api/sync/account") || p.startsWith("/api/sync/account/")) {
            return Optional.of(p.equals("/api/sync/account/characters") ? RouteGroup.CHARACTERS
                    : RouteGroup.ACCOUNT_REFRESH);
        }
        if (under(p, "/api/sync") || p.equals("/api/prices/refresh")) return Optional.of(RouteGroup.TASK_CONTROL);
        if (p.equals("/api/crafting/profit") || p.equals("/api/crafting/profit/resolution")
                || p.equals("/api/crafting/selector-options") || under(p, "/api/crafting/discovery")) {
            return Optional.of(RouteGroup.CRAFTING);
        }
        if (under(p, "/api/crafting") || under(p, "/api/items") || under(p, "/api/resources")
                || under(p, "/api/prices")) {
            return Optional.of(RouteGroup.SHARED_GAME_DATA);
        }
        return Optional.of(RouteGroup.OTHER_API);
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
