package repo;

public final class AppConfig {
    private AppConfig() {}

    // GW2 API
    public static final String API_KEY = EnvConfig.require("GW2_API_KEY");

    // Postgres
    public static final String DB_URL  = EnvConfig.require("DATABASE_URL");
    public static final String DB_USER = EnvConfig.require("DATABASE_USER");
    public static final String DB_PASS = EnvConfig.require("DATABASE_PASSWORD");
}