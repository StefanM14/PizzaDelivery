package it.delivery.optimizer.domain;

import java.time.LocalTime;
import java.util.List;

/**
 * Route assigned to a specific rider, containing the sequence of orders delivered,
 * total duration, total distance, scheduled depot departure time, and return time.
 *
 * @param rider           The rider assigned to this route
 * @param orders          Ordered list of orders delivered along the route
 * @param durationSeconds Estimated duration of the route in seconds
 * @param distanceMeters  Estimated total distance of the route in meters
 * @param departureTime   Scheduled departure time from the depot
 * @param returnTime      Scheduled return time to the depot
 */
public record RiderRoute(
        Rider rider,
        List<Order> orders,
        long durationSeconds,
        long distanceMeters,
        LocalTime departureTime,
        LocalTime returnTime
) {
    public RiderRoute(Rider rider, List<Order> orders, long durationSeconds, long distanceMeters) {
        this(rider, orders, durationSeconds, distanceMeters,
                rider != null ? rider.shiftStart() : null,
                rider != null ? rider.shiftEnd() : null);
    }

    public RiderRoute {
        orders = orders != null ? List.copyOf(orders) : List.of();
    }
}
