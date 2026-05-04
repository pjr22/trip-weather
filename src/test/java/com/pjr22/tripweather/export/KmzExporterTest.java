package com.pjr22.tripweather.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.RouteData;

class KmzExporterTest {

    private final KmzExporter exporter = new KmzExporter(new KmlExporter());

    @Test
    void zipFileContainsExactlyOneEntryNamedDocKml() throws Exception {
        byte[] kmz = exporter.write(fixture());
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(kmz))) {
            ZipEntry first = zin.getNextEntry();
            assertNotNull(first);
            assertEquals("doc.kml", first.getName());
            assertNull(zin.getNextEntry(), "no additional entries");
        }
    }

    @Test
    void docKmlMatchesKmlExporterOutput() throws Exception {
        byte[] kmz = exporter.write(fixture());
        byte[] kmlFromZip;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(kmz))) {
            zin.getNextEntry();
            kmlFromZip = zin.readAllBytes();
        }
        String inside = new String(kmlFromZip, StandardCharsets.UTF_8);
        assertTrue(inside.contains("<kml"));
        assertTrue(inside.contains("Test Route"));
    }

    private static ExportContext fixture() {
        WaypointDto wp1 = new WaypointDto();
        wp1.setSequence(1);
        wp1.setLocationName("Start");
        wp1.setLatitude(45.5);
        wp1.setLongitude(-122.6);
        wp1.setElevation(100.0);

        WaypointDto wp2 = new WaypointDto();
        wp2.setSequence(2);
        wp2.setLocationName("End");
        wp2.setLatitude(45.6);
        wp2.setLongitude(-122.7);
        wp2.setElevation(130.0);

        RouteDto route = new RouteDto();
        route.setName("Test Route");
        route.setWaypoints(List.of(wp1, wp2));

        RouteData routeData = new RouteData();
        routeData.setGeometry(List.of(
                List.of(-122.6, 45.5, 100.0),
                List.of(-122.7, 45.6, 130.0)
        ));

        return new ExportContext(route, routeData, Map.of());
    }
}
