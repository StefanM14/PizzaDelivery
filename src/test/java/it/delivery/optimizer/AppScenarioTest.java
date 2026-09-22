package it.delivery.optimizer;

import it.delivery.optimizer.domain.DeliveryScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AppScenarioTest {

    @Test
    @DisplayName("Should successfully load and deserialize orzinuovi-rush.json with 3 riders")
    void testLoadScenario() throws Exception {
        DeliveryScenario scenario = App.loadScenario();

        assertNotNull(scenario, "Scenario should not be null");
        assertNotNull(scenario.depot(), "Depot should be present");
        assertEquals("Piazza Vittorio Emanuele II, Orzinuovi", scenario.depot().location().name());
        assertEquals(45.4012, scenario.depot().location().latitude());
        assertEquals(9.9248, scenario.depot().location().longitude());

        assertEquals(3, scenario.riders().size(), "Should load 3 riders");
        assertEquals(8, scenario.riders().get(0).pizzaCapacity(), "Rider capacity should be 8");
        assertEquals(8, scenario.riders().get(1).pizzaCapacity(), "Rider capacity should be 8");
        assertEquals(8, scenario.riders().get(2).pizzaCapacity(), "Rider capacity should be 8");
        assertEquals("Rider-Andrea", scenario.riders().get(2).id());

        assertEquals(12, scenario.orders().size(), "Should load 12 orders");

        // Verify total pizza demand does not exceed total fleet capacity (24)
        int totalPizzas = scenario.orders().stream().mapToInt(it.delivery.optimizer.domain.Order::pizzaCount).sum();
        assertEquals(15, totalPizzas, "Total pizzas should match realistic batch size");
        assertFalse(scenario.orders().isEmpty());
    }
}
