package repo;

public final class AppConfig {
    private AppConfig() {}

    // GW2 API
    public static final String API_KEY = "AB41649F-A1A9-014E-9E5E-2AAA31DEC08E298B52C7-53FA-4C3A-BDA1-0A49B05FEAF2";

    // Postgres
    public static final String DB_URL  = "jdbc:postgresql://localhost:5432/GWDatabase";
    public static final String DB_USER = "postgres";
    public static final String DB_PASS = "0";
}
