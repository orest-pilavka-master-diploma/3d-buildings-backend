package com.students.lnu.geotools;

import org.geojson.LngLatAlt;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;

import java.util.List;

public class GeoTools {
    public static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    public static org.locationtech.jts.geom.MultiPolygon convertToMultiPolygon(org.geojson.MultiPolygon geoJsonMultiPolygon) {
        List<List<List<LngLatAlt>>> coordinates = geoJsonMultiPolygon.getCoordinates();
        org.locationtech.jts.geom.Polygon[] polygons = new org.locationtech.jts.geom.Polygon[coordinates.size()];

        for (int i = 0; i < coordinates.size(); i++) {
            polygons[i] = createPolygon(coordinates.get(i));
        }

        return GEOMETRY_FACTORY.createMultiPolygon(polygons);
    }

    private static org.locationtech.jts.geom.Polygon createPolygon(List<List<LngLatAlt>> rings) {
        if (rings.isEmpty()) {
            throw new IllegalArgumentException("Polygon must have at least one ring");
        }

        LinearRing shell = createLinearRing(rings.getFirst());
        LinearRing[] holes = new LinearRing[rings.size() - 1];

        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = createLinearRing(rings.get(i));
        }

        return GEOMETRY_FACTORY.createPolygon(shell, holes);
    }

    private static LinearRing createLinearRing(List<LngLatAlt> points) {
        Coordinate[] coordinates = points.stream()
                .map(p -> new Coordinate(p.getLongitude(), p.getLatitude()))
                .toArray(Coordinate[]::new);

        return GEOMETRY_FACTORY.createLinearRing(coordinates);
    }
}
