package repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal environment-variable / .env loader.
 *
 * Real process environment variables take priority; a ".env" file in the
 * working directory (same cwd-relative convention already used elsewhere
 * in this project, e.g. crafting_graph_cache.json) is used as a fallback
 * for local development. See .env.example for the expected keys.
 */
final class EnvConfig {

    private static final Map<String, String> DOTENV = loadDotEnvFile();

    private EnvConfig() {}

    static String require(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = DOTENV.get(key);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required configuration value: " + key +
                            ". Set it as an environment variable, or add it to a .env file " +
                            "in the project root (see .env.example)."
            );
        }
        return value;
    }

    private static Map<String, String> loadDotEnvFile() {
        Map<String, String> values = new HashMap<>();

        Path path = Path.of(".env");
        if (!Files.exists(path)) {
            return values;
        }

        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;

                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();

                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }

                values.put(key, value);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read .env file: " + path.toAbsolutePath(), e);
        }

        return values;
    }
}
