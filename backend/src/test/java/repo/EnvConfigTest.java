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
}
