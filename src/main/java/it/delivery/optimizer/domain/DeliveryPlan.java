package it.delivery.optimizer.domain;

import java.util.List;

/**
 * Optimized delivery plan storing solver solutions.
 *
 * @param routes               List of routes assigned to riders
 * @param totalDurationSeconds Cumulative duration of all routes in seconds
 * @param totalDistanceMeters  Cumulative distance of all routes in meters
 */
public record DeliveryPlan(
        List<RiderRoute> routes,
        long totalDurationSeconds,
        long totalDistanceMeters
) {
    public DeliveryPlan {
        routes = routes != null ? List.copyOf(routes) : List.of();
    }
}
