package repo;

import java.nio.file.Path;

public final class AppConfig {
    private AppConfig() {}

    // GW2 API
    // Retained local entry points may use a configured key. Browser account operations supply
    // their own transient key; loading database/global configuration must not require one.
    public static final String API_KEY = EnvConfig.optional("GW2_API_KEY", "");

    // Postgres
    public static final String DB_URL  = EnvConfig.require("DATABASE_URL");
    public static final String DB_USER = EnvConfig.require("DATABASE_USER");
    public static final String DB_PASS = EnvConfig.require("DATABASE_PASSWORD");
    public static final int DB_POOL_SIZE = Integer.parseInt(EnvConfig.optional("DATABASE_POOL_SIZE", "8"));
    public static final long DB_POOL_TIMEOUT_MS = Long.parseLong(EnvConfig.optional("DATABASE_POOL_TIMEOUT_MS", "15000"));

    // Icon cache
    private static final String DEFAULT_ICON_CACHE_DIR =
            Path.of(System.getProperty("user.home"), ".nebet-gw2-tool", "icons").toString();
    public static final String ICON_CACHE_DIR = EnvConfig.optional("ICON_CACHE_DIR", DEFAULT_ICON_CACHE_DIR);
}
