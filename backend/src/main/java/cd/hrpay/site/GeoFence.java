package cd.hrpay.site;

/** A circular area on the Earth's surface. */
public record GeoFence(double centerLat, double centerLng, int radiusMeters) {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    public GeoFence {
        if (centerLat < -90 || centerLat > 90 || centerLng < -180 || centerLng > 180) {
            throw new IllegalArgumentException("Center is not a valid coordinate.");
        }
        if (radiusMeters <= 0) {
            throw new IllegalArgumentException("Radius must be positive.");
        }
    }

    /** Great-circle distance in meters (haversine formula). */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public boolean contains(double lat, double lng) {
        return distanceMeters(centerLat, centerLng, lat, lng) <= radiusMeters;
    }
}
