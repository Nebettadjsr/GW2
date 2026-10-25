package repo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvConfigTest {

    @Test
    void optional_returnsDefaultValue_whenKeyIsNotSetAnywhere() {
        String result = EnvConfig.optional("NEBET_GW2_TOOL_UNSET_TEST_KEY", "fallback-value");

        assertEquals("fallback-value", result);
    }

    @Test
    void locateDotEnv_usesOwnFile_thenRepositoryRootOnlyFromModuleDirectory(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Path backend = Files.createDirectory(root.resolve("backend"));
        Files.writeString(root.resolve(".env"), "A=1\n");

        // Not a module directory (no pom.xml): never reaches into the parent.
        assertEquals(backend.resolve(".env"), EnvConfig.locateDotEnv(backend));

        Files.writeString(backend.resolve("pom.xml"), "<project/>");
        assertEquals(root.toAbsolutePath().normalize().resolve(".env"), EnvConfig.locateDotEnv(backend));

        Files.writeString(backend.resolve(".env"), "A=2\n");
        assertEquals(backend.resolve(".env"), EnvConfig.locateDotEnv(backend));
    }

    @Test
    void resolve_prefersProcessValue_thenDotEnv_andTreatsBlankAsAbsent() {
        java.util.Map<String, String> dotEnv = java.util.Map.of("K", "from-file", "BLANK", "  ");

        assertEquals(java.util.Optional.of("from-process"), EnvConfig.resolve("from-process", dotEnv, "K"));
        assertEquals(java.util.Optional.of("from-file"), EnvConfig.resolve(null, dotEnv, "K"));
        assertEquals(java.util.Optional.of("from-file"), EnvConfig.resolve("   ", dotEnv, "K"));
        assertEquals(java.util.Optional.empty(), EnvConfig.resolve(null, dotEnv, "BLANK"));
        assertEquals(java.util.Optional.empty(), EnvConfig.resolve("", dotEnv, "MISSING"));
    }

    @Test
    void parseDotEnv_readsKeyValuesSkippingCommentsBlanksAndMalformedLines(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, String.join("\n",
                "# comment",
                "",
                "PLAIN = value with spaces ",
                "QUOTED=\"in quotes\"",
                "ONE_QUOTE=\"",
                "=novalue-key",
                "no-equals-sign",
                "EMPTY=",
                "LAST=a=b"));

        java.util.Map<String, String> values = EnvConfig.parseDotEnv(file);

        assertEquals(java.util.Map.of(
                "PLAIN", "value with spaces",
                "QUOTED", "in quotes",
                "ONE_QUOTE", "\"",
                "EMPTY", "",
                "LAST", "a=b"), values);
    }

    @Test
    void parseDotEnv_missingFileIsEmpty_andUnreadablePathFailsNamingTheFile(@org.junit.jupiter.api.io.TempDir Path dir) {
        assertEquals(java.util.Map.of(), EnvConfig.parseDotEnv(dir.resolve("absent.env")));

        // a directory exists but cannot be read as a file
        RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> EnvConfig.parseDotEnv(dir));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("Failed to read .env file"));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getCause() instanceof java.io.IOException);
    }

    @Test
    void require_failsNamingTheMissingKey() {
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> EnvConfig.require("NEBET_GW2_TOOL_UNSET_TEST_KEY"));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("NEBET_GW2_TOOL_UNSET_TEST_KEY"));
    }
}
