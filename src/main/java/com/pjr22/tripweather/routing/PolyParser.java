package com.pjr22.tripweather.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the Geofabrik / Osmosis ".poly" polygon-filter format. The
 * format is defined at <a href="https://wiki.openstreetmap.org/wiki/Osmosis/Polygon_Filter_File_Format">
 * the Osmosis wiki</a>; structure:
 *
 * <pre>
 *   region_name
 *   1
 *      lon  lat
 *      lon  lat
 *      ...
 *   END
 *   !2
 *      lon  lat
 *      ...
 *   END
 *   END
 * </pre>
 *
 * Each ring section is preceded by a name. A leading {@code !} marks an inner
 * ring (hole) of the previous outer ring. The trailing {@code END} closes
 * the file. Whitespace between coordinates is arbitrary; coordinates are
 * decimal lon/lat in WGS84.
 *
 * Output is a JTS {@link MultiPolygon} in SRID 4326. Single-polygon files
 * (the common case for US states) are wrapped in a one-element multipolygon
 * so the output type is uniform.
 */
public final class PolyParser {

    private static final GeometryFactory GEOM_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private PolyParser() {
        // utility
    }

    public static MultiPolygon parse(Reader source) throws IOException {
        try (BufferedReader in = new BufferedReader(source)) {
            String header = readNonBlank(in);
            if (header == null) {
                throw new IOException("Empty .poly file");
            }
            // First line is the region name; we don't care about it.

            List<Polygon> polygons = new ArrayList<>();
            List<LinearRing> currentInners = new ArrayList<>();
            LinearRing currentOuter = null;

            String token;
            while ((token = readNonBlank(in)) != null) {
                if (token.equals("END")) {
                    // Top-level END: file is done.
                    break;
                }
                boolean isHole = token.startsWith("!");
                LinearRing ring = readRing(in);

                if (isHole) {
                    if (currentOuter == null) {
                        throw new IOException("Inner ring '" + token
                                + "' has no preceding outer ring");
                    }
                    currentInners.add(ring);
                } else {
                    // Starting a new outer ring; flush the previous polygon.
                    if (currentOuter != null) {
                        polygons.add(GEOM_FACTORY.createPolygon(currentOuter,
                                currentInners.toArray(new LinearRing[0])));
                        currentInners.clear();
                    }
                    currentOuter = ring;
                }
            }

            if (currentOuter != null) {
                polygons.add(GEOM_FACTORY.createPolygon(currentOuter,
                        currentInners.toArray(new LinearRing[0])));
            }

            if (polygons.isEmpty()) {
                throw new IOException("No polygons found in .poly file");
            }

            MultiPolygon mp = GEOM_FACTORY.createMultiPolygon(
                    polygons.toArray(new Polygon[0]));
            mp.setSRID(4326);
            return mp;
        }
    }

    private static LinearRing readRing(BufferedReader in) throws IOException {
        List<Coordinate> coords = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equals("END")) {
                if (coords.size() < 3) {
                    throw new IOException("Ring closed with only " + coords.size()
                            + " coordinate(s); need at least 3");
                }
                // .poly rings may be open (first != last); JTS LinearRing
                // requires explicit closure.
                Coordinate first = coords.get(0);
                Coordinate last = coords.get(coords.size() - 1);
                if (!first.equals2D(last)) {
                    coords.add(new Coordinate(first));
                }
                return GEOM_FACTORY.createLinearRing(
                        coords.toArray(new Coordinate[0]));
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                throw new IOException("Malformed coordinate line: '" + line + "'");
            }
            double lon;
            double lat;
            try {
                lon = Double.parseDouble(parts[0]);
                lat = Double.parseDouble(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed coordinate line: '" + line + "'", e);
            }
            coords.add(new Coordinate(lon, lat));
        }
        throw new IOException("Unexpected EOF inside ring (missing END)");
    }

    private static String readNonBlank(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }
}
