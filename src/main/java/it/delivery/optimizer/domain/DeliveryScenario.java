package it.delivery.optimizer.domain;

import java.util.List;

/**
 * Encapsulates a complete delivery scenario loaded from JSON or external sources.
 *
 * @param depot   The pizzeria depot
 * @param riders  Available delivery riders
 * @param orders  Pending customer orders
 */
public record DeliveryScenario(
        Depot depot,
        List<Rider> riders,
        List<Order> orders
) {
    public DeliveryScenario {
        riders = riders != null ? List.copyOf(riders) : List.of();
        orders = orders != null ? List.copyOf(orders) : List.of();
    }
}
