# 🍕 Pizza Delivery Route Optimizer — Pizzeria Il Cantinone

[![Live Demo](https://img.shields.io/badge/Live_Demo-GitHub_Pages-brightgreen?logo=github&style=flat-square)](https://stefanm14.github.io/PizzaDelivery/)
![Java](https://img.shields.io/badge/java-21-blue.svg?style=flat-square)
![Maven](https://img.shields.io/badge/maven-3.5+-C71A22.svg?style=flat-square)
![OR-Tools](https://img.shields.io/badge/Google_OR--Tools-Optimization-4285F4.svg?style=flat-square)
![JUnit](https://img.shields.io/badge/JUnit-5-25A162.svg?style=flat-square)

An Operations Research & Combinatorial Optimization system solving the **Vehicle Routing Problem with Time Windows (VRPTW)** under strict thermal freshness and kitchen synchronization constraints.

👉 **[Explore the Live Interactive Route Map](https://stefanm14.github.io/PizzaDelivery/)**

<p align="center">
  <a href="https://stefanm14.github.io/PizzaDelivery/">
    <img src="delivery-map-screenshot.png" alt="Delivery Map Screenshot" width="800">
  </a>
</p>

---

## 📌 Motivation & Case Study: *Pizzeria Il Cantinone*

This project is inspired by my family's pizzeria, **Pizzeria Il Cantinone**, in my hometown of **Orzinuovi** (Brescia, Italy). 

During busy weekend dinner hours, deliveries must reach customers across the town center and nearby rural hamlets (*frazioni* such as Barco, Coniolo, Pudiano, and Ovanengo). Balancing tight delivery deadlines, driver capacities, and food temperature (aiming to deliver within ~35 minutes of baking) creates an interesting combinatorial challenge.

I used our pizzeria's setting as a concrete case study to model and test a **Capacitated Vehicle Routing Problem with Time Windows (CVRPTW)** using real road network data.

---

## 🔬 Mathematical Formulation & Domain Constraints

The system models the delivery network as a complete directed graph $G = (V, A)$, where vertex $0$ represents the pizzeria depot (Piazza Vittorio Emanuele II, Orzinuovi) and $V \setminus \{0\} = \{1, \dots, n\}$ represents customer orders. A fleet of riders $K = \{1, \dots, m\}$ serves the demand.

### 1. Objective Function
Minimize the global cost function combining cumulative travel duration across all active routes and a penalty for any tardiness beyond customer deadlines:

$$\min \quad \sum_{k \in K} \sum_{(i,j) \in A} c_{ij} \, x_{ijk} + \lambda \sum_{i \in V \setminus \{0\}} \max\left(0, T_i - d_i\right)$$

Where:
- $c_{ij}$: Real driving travel duration from node $i$ to node $j$ (queried via OSRM).
- $x_{ijk} \in \{0, 1\}$: Binary decision variable indicating if rider $k$ travels directly from $i$ to $j$.
- $T_i$: Arrival time at customer $i$.
- $d_i$: Promised customer deadline.
- $\lambda$: High penalty factor for tardiness ($\lambda = 5000\text{ cost units/sec}$).

### 2. Operational Constraints
* **Vehicle Thermal Bag Capacity (Hard):** Each rider carries insulated thermal boxes holding up to $Q_k = 8$ pizzas:
  $$\sum_{i \in V \setminus \{0\}} q_i \, y_{ik} \le Q_k \quad \forall k \in K$$
* **Kitchen Oven Synchronization (Hard):** A rider cannot depart the pizzeria until **all** pizzas assigned to their route have finished baking in the wood-fired oven:
  $$T_{\text{start}, k} \ge \max_{i \in \text{Route}(k)} (\text{readyTime}_i) \quad \forall k \in K$$
* **Customer Time Windows (Soft with heavy penalty):** Orders must arrive within the agreed delivery window $[e_i, d_i]$. Early arrivals wait; late arrivals incur heavy penalties.
* **Thermal Freshness Preservation:** To preserve crust texture and mozzarella temperature, an order's elapsed transit time from oven exit to doorstep is constrained:
  $$T_i - \text{readyTime}_i \le 35\text{ minutes}$$
* **Rider Shift Windows (Hard):** Riders must return to the depot in Piazza Vittorio Emanuele II before their scheduled shift ends:
  $$T_{\text{end}, k} \le \text{shiftEnd}_k \quad \forall k \in K$$

---

## 🛠️ Technology Stack & Architecture

- **Language & Runtime:** Java 21 (Records, pattern matching, modern `java.net.http.HttpClient`, `java.time`)
- **Optimization Engine:** **Google OR-Tools** (Constraint Programming / Routing Library)
  - *Initial Solution:* Path Cheapest Arc (`PATH_CHEAPEST_ARC`)
  - *Metaheuristic:* Guided Local Search (`GUIDED_LOCAL_SEARCH`) for escaping local minima in complex time-window landscapes.
- **Routing & Geodesics:**
  - **OSRM (Open Source Routing Machine) Table Service:** Live highway driving distance and duration matrices computed on actual road networks.
  - **In-Memory Cache & Geodesic Fallback:** Thread-safe matrix caching with an automated Haversine detour model ($factor = 1.35$) if public routing nodes are unreachable.
- **Data Serialization:** Jackson (`jackson-databind`, `jackson-datatype-jsr310`)
- **Testing:** **JUnit 5** with parameterized tests validating capacity limits, time-window compliance, and edge cases.
- **Visualization:** Leaflet.js interactive maps with dynamic color-coded polyline itineraries, popup details, and summary statistics.

---

## 📂 Scenario: *The Orzinuovi Rush*

The repository includes a real-world benchmark scenario based on a Saturday night dinner rush at *Il Cantinone*:
- **Depot:** Piazza Vittorio Emanuele II, Orzinuovi ($45.4012^\circ\text{N}, 9.9248^\circ\text{E}$).
- **Fleet:** 3 delivery riders (Marco, Luca, Andrea), each equipped with an 8-pizza thermal transport box.
- **Orders:** 12 batch orders spread across Orzinuovi center, Barco, Pudiano, Coniolo, and Ovanengo, totaling 15 pizzas with staggered baking completion times.

---

## 🚀 Getting Started

### Prerequisites
- **Java Development Kit (JDK) 21** or later
- **Maven 3.5+** (The repository includes the Maven Wrapper `./mvnw`, so local Maven installation is optional)

### Build & Run
1. **Clone the repository:**
   ```bash
   git clone https://github.com/StefanM14/PizzaDelivery.git
   cd PizzaDelivery
   ```

2. **Compile and execute test suite:**
   ```bash
   ./mvnw clean test
   ```

3. **Run the optimization engine:**
   ```bash
   ./mvnw exec:java
   ```
   *(Or run `it.delivery.optimizer.App` directly from your IDE).*

4. **Inspect the Output:**
   - The CLI outputs a detailed tabular itinerary showing synchronized departure, arrival times, delivery status, and fleet metrics.
   - Open the generated `delivery-map.html` in any browser (or view the [Live Demo](https://stefanm14.github.io/PizzaDelivery/)) to explore routes interactively.

## 📝 License
This project has no license.

