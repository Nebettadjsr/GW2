package repo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvConfigTest {

    @Test
    void optional_returnsDefaultValue_whenKeyIsNotSetAnywhere() {
        String result = EnvConfig.optional("NEBET_GW2_TOOL_UNSET_TEST_KEY", "fallback-value");

        assertEquals("fallback-value", result);
    }
}
