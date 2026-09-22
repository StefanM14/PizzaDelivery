package it.delivery.optimizer.domain;

import java.time.LocalTime;

/**
 * Pizza delivery order with destination, time windows, and volume.
 *
 * @param id           Unique order identifier
 * @param destination  Delivery location
 * @param readyTime    Time when the order is ready at the pizzeria for pickup
 * @param deadlineTime Latest acceptable delivery time to customer
 * @param pizzaCount   Number of pizzas in the order
 */
public record Order(
        String id,
        Location destination,
        LocalTime readyTime,
        LocalTime deadlineTime,
        int pizzaCount
) {
}
