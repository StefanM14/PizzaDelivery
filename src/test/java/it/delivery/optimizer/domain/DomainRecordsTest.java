package it.delivery.optimizer.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainRecordsTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("Location toOsrmCoord() should return 'longitude,latitude'")
    void testLocationToOsrmCoord() {
        Location loc = new Location("Piazza Garibaldi, Orzinuovi", 45.4011, 9.9238);

        assertEquals("Piazza Garibaldi, Orzinuovi", loc.name());
        assertEquals(45.4011, loc.latitude());
        assertEquals(9.9238, loc.longitude());
        assertEquals("9.9238,45.4011", loc.toOsrmCoord());
    }

    @Test
    @DisplayName("Order record should correctly hold order information")
    void testOrderRecord() {
        Location customerLoc = new Location("Via Roma 10, Soncino", 45.4002, 9.8711);
        LocalTime ready = LocalTime.of(19, 0);
        LocalTime deadline = LocalTime.of(19, 45);
        Order order = new Order("ORD-001", customerLoc, ready, deadline, 3);

        assertEquals("ORD-001", order.id());
        assertEquals(customerLoc, order.destination());
        assertEquals(ready, order.readyTime());
        assertEquals(deadline, order.deadlineTime());
        assertEquals(3, order.pizzaCount());
    }

    @Test
    @DisplayName("Rider record should hold capacity and shift window")
    void testRiderRecord() {
        Rider rider = new Rider("RIDER-1", 5, LocalTime.of(18, 30), LocalTime.of(22, 30));

        assertEquals("RIDER-1", rider.id());
        assertEquals(5, rider.pizzaCapacity());
        assertEquals(LocalTime.of(18, 30), rider.shiftStart());
        assertEquals(LocalTime.of(22, 30), rider.shiftEnd());
    }

    @Test
    @DisplayName("Depot record and default Orzinuovi instance")
    void testDepotRecord() {
        Depot defaultDepot = Depot.DEFAULT_ORZINUOVI;
        assertNotNull(defaultDepot);
        assertEquals("Pizzeria Orzinuovi", defaultDepot.name());
        assertTrue(defaultDepot.location().name().contains("Orzinuovi"));

        Depot customDepot = new Depot("Cantinone Orzinuovi", new Location("Cantinone", 45.4015, 9.9240));
        assertEquals("Cantinone Orzinuovi", customDepot.name());
    }

    @Test
    @DisplayName("DeliveryPlan and RiderRoute should hold routes and solver metrics")
    void testDeliveryPlanAndRiderRoute() {
        Rider rider = new Rider("R-1", 4, LocalTime.of(19, 0), LocalTime.of(22, 0));
        Location dest = new Location("Via Milano 5", 45.4050, 9.9210);
        Order order = new Order("O-1", dest, LocalTime.of(19, 10), LocalTime.of(19, 40), 2);

        RiderRoute route = new RiderRoute(rider, List.of(order), 600, 3500);
        assertEquals(1, route.orders().size());
        assertEquals(600, route.durationSeconds());
        assertEquals(3500, route.distanceMeters());

        DeliveryPlan plan = new DeliveryPlan(List.of(route), 600, 3500);
        assertEquals(1, plan.routes().size());
        assertEquals(600, plan.totalDurationSeconds());
        assertEquals(3500, plan.totalDistanceMeters());
    }

    @Test
    @DisplayName("Jackson should serialize and deserialize records correctly")
    void testJacksonJsonSerialization() throws Exception {
        Location dest = new Location("Soncino", 45.4002, 9.8711);
        Order order = new Order("ORD-100", dest, LocalTime.of(19, 15), LocalTime.of(19, 45), 4);
        Rider rider = new Rider("R-1", 6, LocalTime.of(19, 0), LocalTime.of(23, 0));
        RiderRoute route = new RiderRoute(rider, List.of(order), 1200, 8500);
        DeliveryPlan plan = new DeliveryPlan(List.of(route), 1200, 8500);

        String json = objectMapper.writeValueAsString(plan);
        assertNotNull(json);

        DeliveryPlan deserialized = objectMapper.readValue(json, DeliveryPlan.class);
        assertEquals(plan.totalDurationSeconds(), deserialized.totalDurationSeconds());
        assertEquals(plan.totalDistanceMeters(), deserialized.totalDistanceMeters());
        assertEquals(1, deserialized.routes().size());
        assertEquals("ORD-100", deserialized.routes().get(0).orders().get(0).id());
        assertEquals("9.8711,45.4002", deserialized.routes().get(0).orders().get(0).destination().toOsrmCoord());
    }
}
