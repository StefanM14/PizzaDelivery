package it.delivery.optimizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {

    @Test
    @DisplayName("Sample test verifying test suite setup and assertions")
    void testAppInitialization() {
        App app = new App();
        assertNotNull(app, "App instance should not be null");
        assertTrue(true, "Test framework is operational");
    }
}
