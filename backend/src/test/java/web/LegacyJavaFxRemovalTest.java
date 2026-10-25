package web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyJavaFxRemovalTest {

    @Test
    void productionClasspathDoesNotContainTheLegacyJavaFxApplicationEntryPoint() {
        assertNull(Thread.currentThread().getContextClassLoader().getResource("Gw2App.class"),
                "the retired JavaFX application entry point must not be packaged");
    }
}
