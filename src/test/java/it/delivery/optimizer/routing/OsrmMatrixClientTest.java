package it.delivery.optimizer.routing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import it.delivery.optimizer.domain.Location;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsrmMatrixClientTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicInteger requestCount = new AtomicInteger(0);

    private OsrmMatrixClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/table/v1/driving/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();

                // Mock OSRM table response for 3 points
                String responseJson = """
                        {
                          "code": "Ok",
                          "durations": [
                            [0.0, 120.4, 450.8],
                            [118.2, 0.0, 320.1],
                            [460.0, 315.6, 0.0]
                          ],
                          "distances": [
                            [0.0, 1250.6, 4800.2],
                            [1230.0, 0.0, 3500.9],
                            [4850.1, 3510.4, 0.0]
                          ]
                        }
                        """;

                byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        requestCount.set(0);
        String testBaseUrl = "http://localhost:" + port + "/table/v1/driving/";
        client = new OsrmMatrixClient(testBaseUrl);
    }

    @Test
    @DisplayName("Should parse duration (seconds) and distance (meters) into long[][]")
    void testGetMatricesSuccess() throws Exception {
        List<Location> locations = List.of(
                new Location("Pizzeria", 45.4011, 9.9238),
                new Location("Customer 1", 45.4050, 9.9300),
                new Location("Customer 2", 45.4120, 9.9450)
        );

        OsrmMatrixClient.MatrixResult result = client.getMatrices(locations);

        assertNotNull(result);
        assertEquals(3, result.durationMatrix().length);
        assertEquals(3, result.distanceMatrix().length);

        // Check rounded long values (duration in seconds)
        assertEquals(0L, result.durationMatrix()[0][0]);
        assertEquals(120L, result.durationMatrix()[0][1]);
        assertEquals(451L, result.durationMatrix()[0][2]);

        // Check rounded long values (distance in meters)
        assertEquals(0L, result.distanceMatrix()[0][0]);
        assertEquals(1251L, result.distanceMatrix()[0][1]);
        assertEquals(4800L, result.distanceMatrix()[0][2]);

        assertEquals(1, requestCount.get(), "First call should invoke HTTP server");
    }

    @Test
    @DisplayName("Should use in-memory cache for repeated calls with same coordinates")
    void testInMemoryCaching() throws Exception {
        List<Location> locations = List.of(
                new Location("Depot", 45.4011, 9.9238),
                new Location("Client", 45.4050, 9.9300),
                new Location("Client 2", 45.4120, 9.9450)
        );

        // First call - invokes remote API
        OsrmMatrixClient.MatrixResult firstCall = client.getMatrices(locations);
        assertEquals(1, requestCount.get());

        // Second call - should hit cache
        OsrmMatrixClient.MatrixResult secondCall = client.getMatrices(locations);
        assertEquals(1, requestCount.get(), "Second call must NOT make another HTTP request");

        // Verify content matches
        assertArrayEquals(firstCall.durationMatrix()[0], secondCall.durationMatrix()[0]);
        assertArrayEquals(firstCall.distanceMatrix()[0], secondCall.distanceMatrix()[0]);

        // Verify cache inspection and clearing
        assertTrue(client.getCache().size() >= 2);
        client.clearCache();
        assertEquals(0, client.getCache().size());

        // Call again after clear - triggers HTTP request
        client.getMatrices(locations);
        assertEquals(2, requestCount.get(), "Call after cache clear should invoke HTTP server");
    }

    @Test
    @DisplayName("Edge cases: empty list and single location")
    void testEdgeCases() throws Exception {
        OsrmMatrixClient.MatrixResult emptyResult = client.getMatrices(List.of());
        assertEquals(0, emptyResult.durationMatrix().length);
        assertEquals(0, emptyResult.distanceMatrix().length);

        Location single = new Location("OnlyOne", 45.4011, 9.9238);
        OsrmMatrixClient.MatrixResult singleResult = client.getMatrices(List.of(single));
        assertEquals(1, singleResult.durationMatrix().length);
        assertEquals(0L, singleResult.durationMatrix()[0][0]);
        assertEquals(0L, singleResult.distanceMatrix()[0][0]);

        assertEquals(0, requestCount.get(), "Edge cases should not invoke HTTP server");
    }
}
