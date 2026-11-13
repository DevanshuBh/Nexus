package dev.nexus.query;

/** Viewport as minLon,minLat,maxLon,maxLat (GeoJSON order: longitude first). */
public record Bbox(double minLon, double minLat, double maxLon, double maxLat) {

    public static Bbox parse(String s) {
        String[] p = s.split(",");
        if (p.length != 4) {
            throw new IllegalArgumentException("bbox must be minLon,minLat,maxLon,maxLat");
        }
        Bbox b = new Bbox(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
        boolean ok = b.minLon >= -180 && b.maxLon <= 180 && b.minLat >= -90 && b.maxLat <= 90
                && b.minLon < b.maxLon && b.minLat < b.maxLat;
        if (!ok) {
            throw new IllegalArgumentException("bbox out of range or inverted");
        }
        return b;
    }

    public double centerLon() {
        return (minLon + maxLon) / 2;
    }

    public double centerLat() {
        return (minLat + maxLat) / 2;
    }

    /** Width in km at the latitude where the box is widest, so GEOSEARCH BYBOX never under-covers. */
    public double widthKm() {
        double widestLat = (minLat <= 0 && maxLat >= 0) ? 0 : Math.min(Math.abs(minLat), Math.abs(maxLat));
        return (maxLon - minLon) * 111.32 * Math.cos(Math.toRadians(widestLat)) + 1;
    }

    public double heightKm() {
        return (maxLat - minLat) * 110.57 + 1;
    }

    public boolean contains(double lon, double lat) {
        return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
    }
}
