package repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal environment-variable / .env loader.
 *
 * Real process environment variables take priority; a ".env" file in the
 * working directory (same cwd-relative convention already used elsewhere
 * in this project, e.g. crafting_graph_cache.json) is used as a fallback
 * for local development. When the working directory is the Maven module root
 * ({@code backend/}, recognised by its pom.xml) and has no .env of its own, the
 * repository-root .env is used, so the maintainer's existing configuration keeps
 * working after the move into backend/. See .env.example for the expected keys.
 */
public final class EnvConfig {

    private static final Map<String, String> DOTENV = loadDotEnvFile();

    private EnvConfig() {}

    public static String require(String key) {
        return lookup(key).orElseThrow(() -> new IllegalStateException(
                "Missing required configuration value: " + key +
                        ". Set it as an environment variable, or add it to a .env file " +
                        "in the project root or backend/ (see .env.example)."
        ));
    }

    /** Like {@link #require(String)}, but returns {@code defaultValue} instead of throwing when unset. */
    static String optional(String key, String defaultValue) {
        return lookup(key).orElse(defaultValue);
    }

    private static Optional<String> lookup(String key) {
        return resolve(System.getenv(key), DOTENV, key);
    }

    /** Process value first; a missing or blank one falls back to the .env value; blank everywhere is absent. */
    static Optional<String> resolve(String processValue, Map<String, String> dotEnv, String key) {
        String value = processValue;
        if (value == null || value.isBlank()) {
            value = dotEnv.get(key);
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /** The .env to read for the given working directory: its own, else the repository root's from backend/. */
    static Path locateDotEnv(Path workingDir) {
        Path own = workingDir.resolve(".env");
        if (Files.exists(own) || !Files.exists(workingDir.resolve("pom.xml"))) {
            return own;
        }
        Path parent = workingDir.toAbsolutePath().normalize().getParent();
        Path root = parent == null ? null : parent.resolve(".env");
        return root != null && Files.exists(root) ? root : own;
    }

    private static Map<String, String> loadDotEnvFile() {
        return parseDotEnv(locateDotEnv(Path.of("")));
    }

    static Map<String, String> parseDotEnv(Path path) {
        Map<String, String> values = new HashMap<>();

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
