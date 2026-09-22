package it.delivery.optimizer.domain;

/**
 * Depot representing the base pizzeria (e.g. in Orzinuovi) where orders are prepared and riders start/return.
 *
 * @param name     Name of the depot / pizzeria
 * @param location Geographic location of the depot
 */
public record Depot(
        String name,
        Location location
) {
    /**
     * Default pizzeria location in Orzinuovi (Brescia, Italy).
     */
    public static final Depot DEFAULT_ORZINUOVI = new Depot(
            "Pizzeria Orzinuovi",
            new Location("Pizzeria Orzinuovi", 45.4011, 9.9238)
    );
}
