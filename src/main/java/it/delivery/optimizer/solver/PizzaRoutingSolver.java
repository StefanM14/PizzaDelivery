package it.delivery.optimizer.solver;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.IntExpr;
import com.google.ortools.constraintsolver.IntVar;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.Solver;
import com.google.ortools.constraintsolver.main;
import com.google.protobuf.Duration;
import it.delivery.optimizer.domain.DeliveryPlan;
import it.delivery.optimizer.domain.Depot;
import it.delivery.optimizer.domain.Location;
import it.delivery.optimizer.domain.Order;
import it.delivery.optimizer.domain.Rider;
import it.delivery.optimizer.domain.RiderRoute;
import it.delivery.optimizer.routing.OsrmMatrixClient;

import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * VRPTW (Vehicle Routing Problem with Time Windows) engine for pizza delivery optimization.
 * <p>
 * Hard constraints:
 * <ul>
 *   <li>Rider vehicle pizza capacity cannot be exceeded</li>
 *   <li>Delivery must arrive after order.readyTime and before deadline</li>
 *   <li>Rider vehicle cannot depart depot before order.readyTime for all loaded orders</li>
 *   <li>Rider must return to the depot before shiftEnd</li>
 * </ul>
 * <p>
 * Soft constraints:
 * <ul>
 *   <li>Minimize total travel duration</li>
 *   <li>Penalize late arrivals and thermal degradation beyond 35 minutes</li>
 * </ul>
 */
public class PizzaRoutingSolver {

    static {
        try {
            Loader.loadNativeLibraries();
        } catch (Throwable t) {
            System.err.println("Notice: Google OR-Tools native loader: " + t.getMessage());
        }
    }

    private final OsrmMatrixClient osrmClient;
    private final SolverConfig config;

    /**
     * Solver configuration options.
     *
     * @param searchTimeoutSeconds       Maximum search time limit in seconds
     * @param latePenaltyPerSecond       Cost penalty per second of arriving past deadline
     * @param customerServiceTimeSeconds Time spent at each delivery location (drop-off)
     * @param strictDeadlines            If true, enforce hard deadline upper bounds
     * @param allowDropOrders            If true, allow solver to drop orders that cannot be served
     * @param dropPenalty                Penalty for dropping an order
     */
    public record SolverConfig(
            long searchTimeoutSeconds,
            long latePenaltyPerSecond,
            long customerServiceTimeSeconds,
            boolean strictDeadlines,
            boolean allowDropOrders,
            long dropPenalty
    ) {
        public static SolverConfig defaultConfig() {
            return new SolverConfig(
                    10L,         // 10 seconds search timeout
                    5000L,       // 5000 cost units per second late
                    120L,        // 2 minutes per pizza delivery drop-off
                    false,       // soft deadline by default (penalized tardiness)
                    false,       // don't drop orders unless necessary
                    1_000_000L   // high penalty for unserved order
            );
        }
    }

    public PizzaRoutingSolver() {
        this(new OsrmMatrixClient(), SolverConfig.defaultConfig());
    }

    public PizzaRoutingSolver(OsrmMatrixClient osrmClient) {
        this(osrmClient, SolverConfig.defaultConfig());
    }

    public PizzaRoutingSolver(OsrmMatrixClient osrmClient, SolverConfig config) {
        this.osrmClient = Objects.requireNonNull(osrmClient, "osrmClient cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Optimizes routes by querying the OSRM matrix and solving the VRPTW model.
     *
     * @param depot   Pizzeria depot
     * @param orders  List of orders to deliver
     * @param riders  Available delivery riders
     * @return Optimized DeliveryPlan
     * @throws IOException          if OSRM call fails
     * @throws InterruptedException if OSRM call is interrupted
     */
    public DeliveryPlan solve(Depot depot, List<Order> orders, List<Rider> riders)
            throws IOException, InterruptedException {
        Objects.requireNonNull(depot, "depot cannot be null");
        Objects.requireNonNull(orders, "orders cannot be null");
        Objects.requireNonNull(riders, "riders cannot be null");

        if (orders.isEmpty() || riders.isEmpty()) {
            return new DeliveryPlan(List.of(), 0L, 0L);
        }

        // Prepare location list: index 0 is depot, indices 1..N are order destinations
        List<Location> locations = new ArrayList<>(orders.size() + 1);
        locations.add(depot.location());
        for (Order order : orders) {
            locations.add(order.destination());
        }

        // Fetch duration and distance matrices from OSRM
        OsrmMatrixClient.MatrixResult matrixResult = osrmClient.getMatrices(locations);

        return solve(depot, orders, riders, matrixResult);
    }

    /**
     * Optimizes routes using a precomputed matrix result (useful for offline solving or testing).
     *
     * @param depot        Pizzeria depot
     * @param orders       List of orders to deliver
     * @param riders       Available delivery riders
     * @param matrixResult Precomputed duration and distance matrices
     * @return Optimized DeliveryPlan
     */
    public DeliveryPlan solve(Depot depot, List<Order> orders, List<Rider> riders, OsrmMatrixClient.MatrixResult matrixResult) {
        Objects.requireNonNull(depot, "depot cannot be null");
        Objects.requireNonNull(orders, "orders cannot be null");
        Objects.requireNonNull(riders, "riders cannot be null");
        Objects.requireNonNull(matrixResult, "matrixResult cannot be null");

        if (orders.isEmpty() || riders.isEmpty()) {
            return new DeliveryPlan(List.of(), 0L, 0L);
        }

        long[][] durationMatrix = matrixResult.durationMatrix();
        long[][] distanceMatrix = matrixResult.distanceMatrix();

        int numLocations = orders.size() + 1;
        int numVehicles = riders.size();
        int depotIndex = 0;

        RoutingIndexManager manager = new RoutingIndexManager(numLocations, numVehicles, depotIndex);
        RoutingModel routing = new RoutingModel(manager);
        Solver cpSolver = routing.solver();

        // 1. Soft Constraint & Objective: Transit callback for travel duration
        int transitCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            int toNode = manager.indexToNode(toIndex);
            if (fromNode >= numLocations || toNode >= numLocations) {
                return 0L;
            }
            return durationMatrix[fromNode][toNode];
        });
        routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);

        // 2. Hard Constraint: Rider vehicle pizza capacity
        int demandCallbackIndex = routing.registerUnaryTransitCallback((long fromIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            if (fromNode == 0 || fromNode > orders.size()) {
                return 0L;
            }
            return orders.get(fromNode - 1).pizzaCount();
        });

        long[] vehicleCapacities = new long[numVehicles];
        for (int v = 0; v < numVehicles; v++) {
            vehicleCapacities[v] = riders.get(v).pizzaCapacity();
        }

        routing.addDimensionWithVehicleCapacity(
                demandCallbackIndex,
                0L, // zero slack
                vehicleCapacities,
                true, // start cumul to zero
                "Capacity"
        );

        // 3. Time Dimension: VRPTW
        int timeCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            int toNode = manager.indexToNode(toIndex);
            if (fromNode >= numLocations || toNode >= numLocations) {
                return 0L;
            }
            long travelTime = durationMatrix[fromNode][toNode];
            long serviceTime = (fromNode == 0) ? 0L : config.customerServiceTimeSeconds();
            return travelTime + serviceTime;
        });

        long maxTimeSpan = 86400L * 2; // Up to 48 hours for overnight consistency
        routing.addDimension(
                timeCallbackIndex,
                7200L,       // Up to 2 hours of waiting slack
                maxTimeSpan, // Capacity of dimension
                false,      // Don't force start to zero
                "Time"
        );

        RoutingDimension timeDimension = routing.getDimensionOrDie("Time");

        // Rider shift windows (Start and End at depot)
        for (int v = 0; v < numVehicles; v++) {
            Rider rider = riders.get(v);
            long shiftStart = toSeconds(rider.shiftStart());
            long shiftEnd = toSeconds(rider.shiftEnd());
            if (shiftEnd <= shiftStart) {
                shiftEnd += 86400L; // Overnight shift
            }

            long startNodeIndex = routing.start(v);
            long endNodeIndex = routing.end(v);

            timeDimension.cumulVar(startNodeIndex).setRange(shiftStart, shiftEnd);
            // Hard constraint: rider must return to depot before shiftEnd
            timeDimension.cumulVar(endNodeIndex).setRange(shiftStart, shiftEnd);
        }

        // Order time windows, ready-time linkage, and penalties
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            int nodeIndex = i + 1;
            long routingIndex = manager.nodeToIndex(nodeIndex);

            long readyTime = toSeconds(order.readyTime());
            long deadlineTime = toSeconds(order.deadlineTime());
            if (deadlineTime <= readyTime) {
                deadlineTime += 86400L;
            }

            // Hard constraint: vehicle cannot arrive before order.readyTime
            timeDimension.cumulVar(routingIndex).setMin(readyTime);

            // Hard constraint: if vehicle v is assigned this order, vehicle start cumul >= readyTime
            // (the rider cannot leave the depot before the pizza is baked)
            for (int v = 0; v < numVehicles; v++) {
                IntVar isVehicleV = cpSolver.makeIsEqualCstVar(routing.vehicleVar(routingIndex), v);
                IntExpr minStart = cpSolver.makeProd(isVehicleV, readyTime);
                cpSolver.addConstraint(cpSolver.makeGreaterOrEqual(timeDimension.cumulVar(routing.start(v)), minStart));
            }

            // Thermal freshness constraint: pizza should arrive within 35 minutes (2100s) of baking
            long thermalDeadline = readyTime + 2100L;
            long effectiveDeadline = Math.min(deadlineTime, thermalDeadline);

            // Deadline handling
            if (config.strictDeadlines()) {
                timeDimension.cumulVar(routingIndex).setMax(effectiveDeadline);
            } else {
                timeDimension.setCumulVarSoftUpperBound(routingIndex, effectiveDeadline, config.latePenaltyPerSecond());
            }

            if (config.allowDropOrders()) {
                routing.addDisjunction(new long[]{routingIndex}, config.dropPenalty());
            }
        }

        // Configure search parameters
        RoutingSearchParameters searchParameters = main.defaultRoutingSearchParameters()
                .toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(Duration.newBuilder().setSeconds(config.searchTimeoutSeconds()).build())
                .build();

        Assignment solution = routing.solveWithParameters(searchParameters);

        if (solution == null) {
            return new DeliveryPlan(List.of(), 0L, 0L);
        }

        return extractSolution(routing, manager, solution, timeDimension, orders, riders, durationMatrix, distanceMatrix);
    }

    private DeliveryPlan extractSolution(
            RoutingModel routing,
            RoutingIndexManager manager,
            Assignment solution,
            RoutingDimension timeDimension,
            List<Order> orders,
            List<Rider> riders,
            long[][] durationMatrix,
            long[][] distanceMatrix
    ) {
        List<RiderRoute> routes = new ArrayList<>();
        long totalPlanDuration = 0L;
        long totalPlanDistance = 0L;

        for (int v = 0; v < riders.size(); v++) {
            List<Order> assignedOrders = new ArrayList<>();
            long routeDuration = 0L;
            long routeDistance = 0L;

            long startNodeIndex = routing.start(v);
            long endNodeIndex = routing.end(v);

            long startSeconds = solution.min(timeDimension.cumulVar(startNodeIndex));
            long endSeconds = solution.min(timeDimension.cumulVar(endNodeIndex));
            LocalTime departureTime = LocalTime.ofSecondOfDay(startSeconds % 86400);
            LocalTime returnTime = LocalTime.ofSecondOfDay(endSeconds % 86400);

            long index = startNodeIndex;
            while (!routing.isEnd(index)) {
                long nextIndex = solution.value(routing.nextVar(index));
                int fromNode = manager.indexToNode(index);
                int toNode = manager.indexToNode(nextIndex);

                if (fromNode < durationMatrix.length && toNode < durationMatrix.length) {
                    routeDuration += durationMatrix[fromNode][toNode];
                    routeDistance += distanceMatrix[fromNode][toNode];
                }

                if (toNode > 0 && toNode <= orders.size()) {
                    assignedOrders.add(orders.get(toNode - 1));
                }
                index = nextIndex;
            }

            if (!assignedOrders.isEmpty()) {
                Rider rider = riders.get(v);
                routes.add(new RiderRoute(rider, assignedOrders, routeDuration, routeDistance, departureTime, returnTime));
                totalPlanDuration += routeDuration;
                totalPlanDistance += routeDistance;
            }
        }

        return new DeliveryPlan(routes, totalPlanDuration, totalPlanDistance);
    }

    private static long toSeconds(LocalTime time) {
        return time.toSecondOfDay();
    }
}
