package it.delivery.optimizer.solver;

import it.delivery.optimizer.domain.DeliveryPlan;
import it.delivery.optimizer.domain.Depot;
import it.delivery.optimizer.domain.Location;
import it.delivery.optimizer.domain.Order;
import it.delivery.optimizer.domain.Rider;
import it.delivery.optimizer.domain.RiderRoute;
import it.delivery.optimizer.routing.OsrmMatrixClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PizzaRoutingSolverTest {

    private Depot depot;
    private Rider rider1;
    private Rider rider2;
    private Order order1;
    private Order order2;
    private Order order3;

    @BeforeEach
    void setUp() {
        depot = new Depot("Pizzeria Orzinuovi", new Location("Depot", 45.4011, 9.9238));

        rider1 = new Rider("RIDER-1", 6, LocalTime.of(19, 0), LocalTime.of(22, 30));
        rider2 = new Rider("RIDER-2", 6, LocalTime.of(19, 0), LocalTime.of(22, 30));

        order1 = new Order("ORD-1", new Location("Customer 1", 45.4050, 9.9300),
                LocalTime.of(19, 10), LocalTime.of(19, 45), 3);
        order2 = new Order("ORD-2", new Location("Customer 2", 45.4120, 9.9450),
                LocalTime.of(19, 15), LocalTime.of(19, 50), 2);
        order3 = new Order("ORD-3", new Location("Customer 3", 45.3950, 9.9150),
                LocalTime.of(19, 20), LocalTime.of(20, 0), 4);
    }

    @Test
    @DisplayName("Should optimize routes using precomputed MatrixResult satisfying capacity and time")
    void testSolveWithPrecomputedMatrix() {
        // 4 locations: 0=Depot, 1=Order1, 2=Order2, 3=Order3
        long[][] durationMatrix = new long[][]{
                {0, 300, 500, 400},
                {300, 0, 250, 600},
                {500, 250, 0, 700},
                {400, 600, 700, 0}
        };
        long[][] distanceMatrix = new long[][]{
                {0, 2000, 3500, 2500},
                {2000, 0, 1800, 4000},
                {3500, 1800, 0, 4500},
                {2500, 4000, 4500, 0}
        };

        OsrmMatrixClient.MatrixResult matrixResult = new OsrmMatrixClient.MatrixResult(durationMatrix, distanceMatrix);
        PizzaRoutingSolver solver = new PizzaRoutingSolver();

        DeliveryPlan plan = solver.solve(depot, List.of(order1, order2, order3), List.of(rider1, rider2), matrixResult);

        assertNotNull(plan, "DeliveryPlan should not be null");
        assertFalse(plan.routes().isEmpty(), "Should assign at least one route");
        assertTrue(plan.totalDurationSeconds() > 0, "Total duration should be positive");
        assertTrue(plan.totalDistanceMeters() > 0, "Total distance should be positive");

        // Verify that capacity constraint is satisfied on every assigned route
        for (RiderRoute route : plan.routes()) {
            int totalPizzasOnRoute = route.orders().stream().mapToInt(Order::pizzaCount).sum();
            assertTrue(totalPizzasOnRoute <= route.rider().pizzaCapacity(),
                    "Route pizzas (" + totalPizzasOnRoute + ") exceeded capacity (" + route.rider().pizzaCapacity() + ")");
        }
    }

    @Test
    @DisplayName("Capacity hard constraint: Orders exceeding single rider capacity split across riders")
    void testCapacityEnforcement() {
        // Rider capacity is 5
        Rider smallRider1 = new Rider("R1", 5, LocalTime.of(19, 0), LocalTime.of(22, 0));
        Rider smallRider2 = new Rider("R2", 5, LocalTime.of(19, 0), LocalTime.of(22, 0));

        Order bigOrderA = new Order("A", new Location("A", 45.4050, 9.9300),
                LocalTime.of(19, 10), LocalTime.of(20, 0), 4);
        Order bigOrderB = new Order("B", new Location("B", 45.4120, 9.9450),
                LocalTime.of(19, 10), LocalTime.of(20, 0), 4);
        // Total pizzas = 8, which cannot fit into a single rider of capacity 5

        long[][] durations = new long[][]{
                {0, 200, 300},
                {200, 0, 250},
                {300, 250, 0}
        };
        long[][] distances = new long[][]{
                {0, 1500, 2000},
                {1500, 0, 1800},
                {2000, 1800, 0}
        };

        PizzaRoutingSolver solver = new PizzaRoutingSolver();
        DeliveryPlan plan = solver.solve(
                depot,
                List.of(bigOrderA, bigOrderB),
                List.of(smallRider1, smallRider2),
                new OsrmMatrixClient.MatrixResult(durations, distances)
        );

        assertNotNull(plan);
        // Both orders must be served, and they must be split across both riders
        assertEquals(2, plan.routes().size(), "Two riders should each be assigned an order due to capacity");
        for (RiderRoute route : plan.routes()) {
            assertEquals(1, route.orders().size(), "Each rider should carry exactly 1 big order");
            assertTrue(route.orders().get(0).pizzaCount() <= route.rider().pizzaCapacity());
        }
    }

@Test
    @DisplayName("Empty orders or empty riders should return an empty delivery plan")
    void testEmptyInputs() throws Exception {
        PizzaRoutingSolver solver = new PizzaRoutingSolver();
        DeliveryPlan emptyOrdersPlan = solver.solve(depot, List.of(), List.of(rider1));
        assertEquals(0, emptyOrdersPlan.routes().size());
        assertEquals(0L, emptyOrdersPlan.totalDurationSeconds());

        DeliveryPlan emptyRidersPlan = solver.solve(depot, List.of(order1), List.of());
        assertEquals(0, emptyRidersPlan.routes().size());
    }
}
