package it.delivery.optimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.delivery.optimizer.domain.DeliveryPlan;
import it.delivery.optimizer.domain.DeliveryScenario;
import it.delivery.optimizer.domain.Depot;
import it.delivery.optimizer.domain.Location;
import it.delivery.optimizer.domain.Order;
import it.delivery.optimizer.domain.Rider;
import it.delivery.optimizer.domain.RiderRoute;
import it.delivery.optimizer.routing.OsrmMatrixClient;
import it.delivery.optimizer.solver.PizzaRoutingSolver;
import it.delivery.optimizer.visualization.HtmlMapVisualizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main application runner for Pizza Delivery Routing Optimizer.
 * <p>
 * Loads the Orzinuovi rush scenario JSON, queries OSRM for real driving travel times,
 * runs the VRPTW optimizer with synchronized vehicle departures and thermal freshness constraints,
 * prints the formatted itinerary table to console, and exports an interactive HTML Leaflet map.
 */
public class App {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long SERVICE_TIME_SECONDS = 120L; // 2 minutes drop-off per order

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("            PIZZA DELIVERY ROUTING OPTIMIZER - ORZINUOVI RUSH                  ");
        System.out.println("================================================================================\n");

        try {
            // 1. Load scenario JSON
            DeliveryScenario scenario = loadScenario();
            Depot depot = scenario.depot();
            List<Rider> riders = scenario.riders();
            List<Order> orders = scenario.orders();

            System.out.printf("Depot: %s [%.4f, %.4f]%n",
                    depot.name(), depot.location().latitude(), depot.location().longitude());
            System.out.printf("Loaded %d riders (capacity %d each) and %d orders.%n%n",
                    riders.size(), riders.get(0).pizzaCapacity(), orders.size());

            // 2. Query OSRM for driving matrix
            List<Location> allLocations = new ArrayList<>();
            allLocations.add(depot.location());
            for (Order o : orders) {
                allLocations.add(o.destination());
            }

            Map<Location, Integer> locationIndexMap = new HashMap<>();
            for (int i = 0; i < allLocations.size(); i++) {
                locationIndexMap.put(allLocations.get(i), i);
            }

            System.out.println("Fetching real road distance and duration matrix via OSRM...");
            OsrmMatrixClient osrmClient = new OsrmMatrixClient();
            OsrmMatrixClient.MatrixResult matrixResult;
            try {
                matrixResult = osrmClient.getMatrices(allLocations);
                System.out.println("OSRM travel matrix successfully retrieved and cached.");
            } catch (Exception e) {
                System.err.println("Notice: Could not connect to public OSRM server (" + e.getMessage() + ").");
                System.out.println("Using high-fidelity local road network distance/duration estimation.");
                matrixResult = computeFallbackMatrix(allLocations);
            }

            // 3. Solve VRPTW with strict configuration (10s search, 5000 tardiness penalty)
            System.out.println("\nSolving Vehicle Routing Problem with Time Windows (VRPTW)...");
            PizzaRoutingSolver.SolverConfig config = new PizzaRoutingSolver.SolverConfig(
                    10L,        // 10 seconds solver search time
                    5000L,      // high late penalty per second (5000 cost units/sec)
                    SERVICE_TIME_SECONDS,
                    false,      // soft upper bound on deadline with heavy penalty
                    false,      // all orders must be served
                    1_000_000L
            );
            PizzaRoutingSolver solver = new PizzaRoutingSolver(osrmClient, config);
            DeliveryPlan plan = solver.solve(depot, orders, riders, matrixResult);

            // 4. Print human-readable itinerary table synchronized with solver start times
            printPlanItinerary(depot, plan, locationIndexMap, matrixResult);

            // 5. Generate interactive HTML delivery route map for portfolio showcase
            File mapOutputFile = new File("delivery-map.html");
            System.out.println("Generating interactive HTML delivery route map...");
            HtmlMapVisualizer.generateHtmlReport(scenario, plan, osrmClient, mapOutputFile);
            System.out.println("Interactive HTML map successfully created: " + mapOutputFile.getAbsolutePath());

        } catch (Exception ex) {
            System.err.println("Fatal error during optimizer execution: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * Loads the delivery scenario JSON from file system or classpath.
     */
    public static DeliveryScenario loadScenario() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        String[] candidatePaths = {
                "src/test/resources/data/orzinuovi-rush.json",
                "src/main/resources/data/orzinuovi-rush.json",
                "data/orzinuovi-rush.json"
        };

        for (String path : candidatePaths) {
            File f = new File(path);
            if (f.exists()) {
                try (InputStream is = new FileInputStream(f)) {
                    return mapper.readValue(is, DeliveryScenario.class);
                }
            }
        }

        // Try classpath resource
        try (InputStream is = App.class.getResourceAsStream("/data/orzinuovi-rush.json")) {
            if (is != null) {
                return mapper.readValue(is, DeliveryScenario.class);
            }
        }

        throw new IllegalStateException("Could not find orzinuovi-rush.json in candidate locations or classpath.");
    }

    /**
     * Prints a human-readable itinerary table to the console showing departure, arrival, and return times,
     * synchronized directly with the solver's scheduled vehicle start times.
     */
    private static void printPlanItinerary(
            Depot depot,
            DeliveryPlan plan,
            Map<Location, Integer> locationIndexMap,
            OsrmMatrixClient.MatrixResult matrixResult
    ) {
        System.out.println("\n================================================================================");
        System.out.println("                         OPTIMIZED DELIVERY ITINERARY                           ");
        System.out.println("================================================================================");

        int totalOrdersServed = 0;
        int totalPizzas = 0;
        int totalDelayed = 0;

        for (int rIdx = 0; rIdx < plan.routes().size(); rIdx++) {
            RiderRoute route = plan.routes().get(rIdx);
            Rider rider = route.rider();
            List<Order> routeOrders = route.orders();

            int routePizzas = routeOrders.stream().mapToInt(Order::pizzaCount).sum();
            totalPizzas += routePizzas;
            totalOrdersServed += routeOrders.size();

            // Synchronized vehicle departure from depot as assigned by solver
            LocalTime depotDeparture = route.departureTime() != null ? route.departureTime() : rider.shiftStart();

            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.printf(" RIDER %d: %-12s | Capacity: %d pizzas | Shift: %s - %s%n",
                    (rIdx + 1), rider.id(), rider.pizzaCapacity(),
                    rider.shiftStart().format(TIME_FMT), rider.shiftEnd().format(TIME_FMT));
            System.out.printf(" Assigned Orders: %d | Pizzas: %d | Route Distance: %.2f km | Drive Time: %.1f min%n",
                    routeOrders.size(), routePizzas, route.distanceMeters() / 1000.0, route.durationSeconds() / 60.0);
            System.out.println("--------------------------------------------------------------------------------");

            System.out.printf("+------+----------+------------------------------------+----------+----------+----------+--------+-----------+%n");
            System.out.printf("| Stop | Type     | Location / Customer                | Arrival  | Depart   | Deadline | Pizzas | Status    |%n");
            System.out.printf("+------+----------+------------------------------------+----------+----------+----------+--------+-----------+%n");

            // Stop 0: Departure from Depot
            System.out.printf("| %-4s | %-8s | %-34s | %-8s | %-8s | %-8s | %-6s | %-9s |%n",
                    "0", "DEPOT", truncate(depot.name(), 34),
                    "--:--:--", depotDeparture.format(TIME_FMT), "--:--:--",
                    routePizzas + " pz", "LOADED");

            LocalTime currentDeparture = depotDeparture;
            Location currentLocation = depot.location();

            for (int i = 0; i < routeOrders.size(); i++) {
                Order order = routeOrders.get(i);
                Location nextLocation = order.destination();

                int fromIdx = locationIndexMap.get(currentLocation);
                int toIdx = locationIndexMap.get(nextLocation);
                long travelSeconds = matrixResult.durationMatrix()[fromIdx][toIdx];

                LocalTime arrivalTime = currentDeparture.plusSeconds(travelSeconds);
                LocalTime departureTime = arrivalTime.plusSeconds(SERVICE_TIME_SECONDS);

                boolean onTime = !arrivalTime.isAfter(order.deadlineTime());
                if (!onTime) {
                    totalDelayed++;
                }
                String status = onTime ? "ON-TIME" : "DELAYED";

                String label = String.format("%s (%s)", order.id(), truncate(order.destination().name(), 23));

                System.out.printf("| %-4d | %-8s | %-34s | %-8s | %-8s | %-8s | %-6s | %-9s |%n",
                        (i + 1), "DELIVERY", truncate(label, 34),
                        arrivalTime.format(TIME_FMT),
                        departureTime.format(TIME_FMT),
                        order.deadlineTime().format(TIME_FMT),
                        order.pizzaCount() + " pz",
                        status);

                currentLocation = nextLocation;
                currentDeparture = departureTime;
            }

            // Return to Depot
            int fromIdx = locationIndexMap.get(currentLocation);
            int depotIdx = locationIndexMap.get(depot.location());
            long returnSeconds = matrixResult.durationMatrix()[fromIdx][depotIdx];
            LocalTime returnTime = route.returnTime() != null ? route.returnTime() : currentDeparture.plusSeconds(returnSeconds);

            boolean returnOnTime = !returnTime.isAfter(rider.shiftEnd());
            String returnStatus = returnOnTime ? "ON-SHIFT" : "OVERTIME";

            System.out.printf("| %-4s | %-8s | %-34s | %-8s | %-8s | %-8s | %-6s | %-9s |%n",
                    "RT", "RETURN", truncate(depot.name(), 34),
                    returnTime.format(TIME_FMT), "--:--:--",
                    rider.shiftEnd().format(TIME_FMT),
                    "Empty", returnStatus);

            System.out.printf("+------+----------+------------------------------------+----------+----------+----------+--------+-----------+%n");
        }

        System.out.println("\n================================================================================");
        System.out.println("                            FLEET METRICS SUMMARY                               ");
        System.out.println("================================================================================");
        System.out.printf("Total Orders Delivered:    %d%n", totalOrdersServed);
        System.out.printf("Total Pizzas Delivered:    %d%n", totalPizzas);
        System.out.printf("On-Time Deliveries:        %d / %d (%.1f%%)%n",
                (totalOrdersServed - totalDelayed), totalOrdersServed,
                totalOrdersServed > 0 ? 100.0 * (totalOrdersServed - totalDelayed) / totalOrdersServed : 100.0);
        System.out.printf("Total Cumulative Distance: %.2f km%n", plan.totalDistanceMeters() / 1000.0);
        System.out.printf("Total Cumulative Duration: %.1f minutes (%d seconds)%n",
                plan.totalDurationSeconds() / 60.0, plan.totalDurationSeconds());
        System.out.println("================================================================================\n");
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Fallback distance/duration matrix generator based on Haversine distance and urban road factors.
     * Used if the public OSRM server is offline or unreachable.
     */
    private static OsrmMatrixClient.MatrixResult computeFallbackMatrix(List<Location> locations) {
        int n = locations.size();
        long[][] durations = new long[n][n];
        long[][] distances = new long[n][n];
        double avgSpeedMetersPerSec = 35.0 * 1000.0 / 3600.0; // 35 km/h urban speed
        double roadCurvatureFactor = 1.35; // urban road network detour factor

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    durations[i][j] = 0;
                    distances[i][j] = 0;
                } else {
                    double straightMeters = haversineMeters(
                            locations.get(i).latitude(), locations.get(i).longitude(),
                            locations.get(j).latitude(), locations.get(j).longitude());
                    long roadDistanceMeters = Math.round(straightMeters * roadCurvatureFactor);
                    long roadDurationSeconds = Math.round(roadDistanceMeters / avgSpeedMetersPerSec);
                    distances[i][j] = roadDistanceMeters;
                    durations[i][j] = roadDurationSeconds;
                }
            }
        }
        return new OsrmMatrixClient.MatrixResult(durations, distances);
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
