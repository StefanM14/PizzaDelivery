package it.delivery.optimizer.domain;

/**
 * Geographic location represented by name, latitude, and longitude.
 *
 * @param name      Descriptive name of the location
 * @param latitude  Geographic latitude (WGS 84)
 * @param longitude Geographic longitude (WGS 84)
 */
public record Location(String name, double latitude, double longitude) {

    /**
     * Formats the coordinate for OSRM queries in "longitude,latitude" format.
     *
     * @return String formatted as longitude,latitude
     */
    public String toOsrmCoord() {
        return longitude + "," + latitude;
    }
}
