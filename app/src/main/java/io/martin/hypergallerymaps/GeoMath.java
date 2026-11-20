package io.martin.hypergallerymaps;

/** Mainland China boundary check used to decide when China-only coordinate offsets apply. */
final class GeoMath {
    /** Rough mainland China polygon as (longitude, latitude) pairs. */
    private static final double[] MAINLAND = {
            73.5, 39.5, 80, 42, 80.5, 45, 87, 49.2, 91, 46, 96, 42.8,
            105, 41.6, 111.5, 45, 116, 47, 116, 49.8, 120, 53.6, 123.5, 53.6, 126, 50, 131, 48, 135, 48.3,
            131, 43, 128, 41.5, 124.3, 40, 122, 39, 123, 37, 122.5, 31, 122, 28, 120, 25.5, 117, 23,
            114.2, 22.5, 111, 20, 109, 18, 108, 21.5, 105.5, 23, 102, 21.2, 99, 22, 97.5, 24, 98.5, 28,
            92, 27.8, 86, 28, 81, 30, 78.5, 32.5, 78, 35.5, 74, 37
    };

    private GeoMath() {
    }

    /** Ray casting over {@link #MAINLAND}. */
    static boolean inMainland(double latitude, double longitude) {
        boolean inside = false;
        int count = MAINLAND.length / 2;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            double xi = MAINLAND[i * 2];
            double yi = MAINLAND[i * 2 + 1];
            double xj = MAINLAND[j * 2];
            double yj = MAINLAND[j * 2 + 1];
            if ((yi > latitude) != (yj > latitude)
                    && longitude < (xj - xi) * (latitude - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    static boolean isValidCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90.0
                && Math.abs(longitude) <= 180.0
                && !(latitude == 0.0 && longitude == 0.0);
    }
}
