package it.delivery.optimizer.domain;

import java.time.LocalTime;

/**
 * Delivery rider with vehicle carrying capacity and working shift window.
 *
 * @param id            Unique rider identifier
 * @param pizzaCapacity Maximum number of pizzas the rider can carry at once
 * @param shiftStart    Start time of the rider's working shift
 * @param shiftEnd      End time of the rider's working shift
 */
public record Rider(
        String id,
        int pizzaCapacity,
        LocalTime shiftStart,
        LocalTime shiftEnd
) {
}
