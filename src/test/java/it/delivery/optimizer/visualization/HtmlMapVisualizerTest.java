package it.delivery.optimizer.visualization;

import it.delivery.optimizer.domain.DeliveryPlan;
import it.delivery.optimizer.domain.DeliveryScenario;
import it.delivery.optimizer.domain.Depot;
import it.delivery.optimizer.domain.Location;
import it.delivery.optimizer.domain.Order;
import it.delivery.optimizer.domain.Rider;
import it.delivery.optimizer.domain.RiderRoute;
import it.delivery.optimizer.routing.OsrmMatrixClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlMapVisualizerTest {

    @Test
    @DisplayName("Should generate valid self-contained HTML map file")
    void testGenerateHtmlReport(@TempDir Path tempDir) throws Exception {
        Depot depot = new Depot("Pizzeria Orzinuovi", new Location("Depot", 45.4012, 9.9248));
        Rider rider = new Rider("Rider-Marco", 8, LocalTime.of(19, 0), LocalTime.of(21, 30));
        Order order = new Order("ORD-01", new Location("Via Roma 12", 45.4028, 9.9235),
                LocalTime.of(19, 5), LocalTime.of(19, 40), 2);

        RiderRoute route = new RiderRoute(rider, List.of(order), 600, 3000, LocalTime.of(19, 10), LocalTime.of(19, 45));
        DeliveryPlan plan = new DeliveryPlan(List.of(route), 600, 3000);
        DeliveryScenario scenario = new DeliveryScenario(depot, List.of(rider), List.of(order));

        File htmlFile = tempDir.resolve("test-delivery-map.html").toFile();
        OsrmMatrixClient osrmClient = new OsrmMatrixClient();

        HtmlMapVisualizer.generateHtmlReport(scenario, plan, osrmClient, htmlFile);

        assertTrue(htmlFile.exists(), "HTML map file should exist");
        assertTrue(htmlFile.length() > 500, "HTML map file should contain content");

        String content = Files.readString(htmlFile.toPath());
        assertTrue(content.contains("<!DOCTYPE html>"));
        assertTrue(content.contains("Leaflet"));
        assertTrue(content.contains("Pizzeria Orzinuovi"));
        assertTrue(content.contains("ORD-01"));
        assertTrue(content.contains("Rider-Marco"));
    }
}
