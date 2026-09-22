package it.delivery.optimizer.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.delivery.optimizer.domain.Location;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Client for the OSRM Table Service.
 * Retrieves distance (meters) and duration (seconds) matrices between locations.
 * Includes an in-memory cache to prevent excessive requests during development.
 */
public class OsrmMatrixClient {

    public static final String DEFAULT_BASE_URL = "http://router.project-osrm.org/table/v1/driving/";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, double[][]> cache;

    /**
     * Holds the duration (seconds) and distance (meters) matrices.
     */
    public record MatrixResult(long[][] durationMatrix, long[][] distanceMatrix) {
    }

    public OsrmMatrixClient() {
        this(DEFAULT_BASE_URL,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(),
                new ConcurrentHashMap<>());
    }

    public OsrmMatrixClient(String baseUrl) {
        this(baseUrl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(),
                new ConcurrentHashMap<>());
    }

    public OsrmMatrixClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this(DEFAULT_BASE_URL, httpClient, objectMapper, new ConcurrentHashMap<>());
    }

    public OsrmMatrixClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper, Map<String, double[][]> cache) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl cannot be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
    }

    /**
     * Computes the duration and distance matrices for the given list of locations.
     * Uses in-memory cached results if previously fetched for the exact sequence of coordinates.
     *
     * @param locations list of locations to compute the distance/duration matrix for
     * @return MatrixResult containing long[][] durationMatrix (seconds) and long[][] distanceMatrix (meters)
     * @throws IOException          if network or JSON parsing fails
     * @throws InterruptedException if HTTP request is interrupted
     */
    public MatrixResult getMatrices(List<Location> locations) throws IOException, InterruptedException {
        if (locations == null || locations.isEmpty()) {
            return new MatrixResult(new long[0][0], new long[0][0]);
        }

        int n = locations.size();
        if (n == 1) {
            return new MatrixResult(new long[][]{{0L}}, new long[][]{{0L}});
        }

        String coordsParam = locations.stream()
                .map(Location::toOsrmCoord)
                .collect(Collectors.joining(";"));

        String durationCacheKey = "durations:" + coordsParam;
        String distanceCacheKey = "distances:" + coordsParam;

        double[][] rawDurations = cache.get(durationCacheKey);
        double[][] rawDistances = cache.get(distanceCacheKey);

        if (rawDurations == null || rawDistances == null) {
            MatrixResponse rawResponse = fetchFromOsrm(coordsParam, n);
            rawDurations = rawResponse.durations;
            rawDistances = rawResponse.distances;

            cache.put(durationCacheKey, rawDurations);
            cache.put(distanceCacheKey, rawDistances);
        }

        long[][] durationMatrix = toLongMatrix(rawDurations, n);
        long[][] distanceMatrix = toLongMatrix(rawDistances, n);

        return new MatrixResult(durationMatrix, distanceMatrix);
    }

    /**
     * Convenience method returning duration matrix in seconds.
     */
    public long[][] getDurationMatrix(List<Location> locations) throws IOException, InterruptedException {
        return getMatrices(locations).durationMatrix();
    }

    /**
     * Convenience method returning distance matrix in meters.
     */
    public long[][] getDistanceMatrix(List<Location> locations) throws IOException, InterruptedException {
        return getMatrices(locations).distanceMatrix();
    }

    /**
     * Returns the in-memory cache map.
     */
    public Map<String, double[][]> getCache() {
        return cache;
    }

    /**
     * Clears all cached matrix results.
     */
    public void clearCache() {
        cache.clear();
    }

    private record MatrixResponse(double[][] durations, double[][] distances) {}

    private MatrixResponse fetchFromOsrm(String coordsParam, int expectedSize) throws IOException, InterruptedException {
        String url = baseUrl + coordsParam + "?annotations=duration,distance";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "PizzaDeliveryOptimizer/1.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("OSRM request failed with HTTP status " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String code = root.path("code").asText("");
        if (!"Ok".equalsIgnoreCase(code)) {
            throw new IOException("OSRM returned non-OK status: '" + code + "' - message: " + root.path("message").asText());
        }

        double[][] durations = parseMatrix(root.path("durations"), expectedSize);
        double[][] distances = parseMatrix(root.path("distances"), expectedSize);

        return new MatrixResponse(durations, distances);
    }

    private double[][] parseMatrix(JsonNode matrixNode, int size) {
        double[][] matrix = new double[size][size];
        for (int i = 0; i < size; i++) {
            JsonNode rowNode = matrixNode.path(i);
            for (int j = 0; j < size; j++) {
                JsonNode cell = rowNode.path(j);
                if (cell.isNumber()) {
                    matrix[i][j] = cell.asDouble();
                } else {
                    // Fallback for null or unreachable nodes in OSRM
                    matrix[i][j] = (i == j) ? 0.0 : -1.0;
                }
            }
        }
        return matrix;
    }

    private long[][] toLongMatrix(double[][] source, int size) {
        long[][] result = new long[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                double val = source[i][j];
                if (val < 0) {
                    // Unreachable or error indicator: assign large default penalty (e.g. 1 day in seconds / 1000km)
                    result[i][j] = 86400L;
                } else {
                    result[i][j] = Math.round(val);
                }
            }
        }
        return result;
    }
}
