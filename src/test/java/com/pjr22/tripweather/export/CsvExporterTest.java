package com.pjr22.tripweather.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.model.WeatherData;

class CsvExporterTest {

    private final CsvExporter exporter = new CsvExporter();

    @Test
    void writesHeaderRowAndOneRowPerWaypoint() {
        ExportContext ctx = fixture();
        String csv = csv(exporter.write(ctx));
        String[] lines = csv.split("\r\n");

        assertEquals(3, lines.length, "BOM+header + 2 data rows");
        assertTrue(lines[0].contains("sequence,name,latitude,longitude"), "header present");
    }

    @Test
    void prefixesUtf8Bom() {
        byte[] out = exporter.write(fixture());
        assertEquals((byte) 0xEF, out[0]);
        assertEquals((byte) 0xBB, out[1]);
        assertEquals((byte) 0xBF, out[2]);
    }

    @Test
    void firstWaypointHasEmptyDistance() {
        String csv = csv(exporter.write(fixture()));
        String[] dataRow = csv.split("\r\n")[1].split(",", -1);
        assertEquals("", dataRow[9], "distance_from_previous_mi for waypoint 1 is empty");
    }

    @Test
    void secondWaypointDistanceConvertedFromMetersToMiles() {
        String csv = csv(exporter.write(fixture()));
        String[] dataRow = csv.split("\r\n")[2].split(",", -1);
        // 16093.4 m = 10 mi (within rounding)
        assertEquals("10.00", dataRow[9]);
    }

    @Test
    void elevationMetersConvertedToFeet() {
        String csv = csv(exporter.write(fixture()));
        String[] dataRow = csv.split("\r\n")[1].split(",", -1);
        // 100 m = 328 ft
        assertEquals("328", dataRow[4]);
    }

    @Test
    void includesWeatherWhenPresent() {
        String csv = csv(exporter.write(fixture()));
        String[] firstWp = csv.split("\r\n")[1].split(",", -1);
        assertEquals("Sunny", firstWp[10]);
        assertEquals("72", firstWp[11]);
        assertEquals("F", firstWp[12]);
    }

    @Test
    void leavesWeatherCellsEmptyWhenWeatherMissing() {
        String csv = csv(exporter.write(fixture()));
        String[] secondWp = csv.split("\r\n")[2].split(",", -1);
        // Waypoint 2 has no weather entry in the map.
        assertEquals("", secondWp[10]);
        assertEquals("", secondWp[11]);
    }

    @Test
    void quotesFieldsContainingCommasAndQuotes() {
        assertEquals("\"a,b\"", CsvExporter.quote("a,b"));
        assertEquals("\"she said \"\"hi\"\"\"", CsvExporter.quote("she said \"hi\""));
        assertEquals("plain", CsvExporter.quote("plain"));
    }

    @Test
    void locationNameWithCommaIsQuotedInOutput() {
        ExportContext ctx = fixture();
        ctx.getRoute().getWaypoints().get(0).setLocationName("Portland, OR");
        String csv = csv(exporter.write(ctx));
        assertTrue(csv.contains("\"Portland, OR\""));
    }

    private static String csv(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ExportContext fixture() {
        WaypointDto wp1 = new WaypointDto();
        wp1.setSequence(1);
        wp1.setLocationName("Start");
        wp1.setLatitude(45.5);
        wp1.setLongitude(-122.6);
        wp1.setElevation(100.0);
        wp1.setDate("2026-06-01");
        wp1.setTime("09:00");
        wp1.setTimezone("America/Los_Angeles");
        wp1.setDurationMin(0);

        WaypointDto wp2 = new WaypointDto();
        wp2.setSequence(2);
        wp2.setLocationName("End");
        wp2.setLatitude(45.6);
        wp2.setLongitude(-122.7);
        wp2.setElevation(200.0);
        wp2.setDate("2026-06-01");
        wp2.setTime("10:00");
        wp2.setTimezone("America/Los_Angeles");
        wp2.setDurationMin(30);

        RouteDto route = new RouteDto();
        route.setName("Test Route");
        route.setWaypoints(List.of(wp1, wp2));

        RouteData routeData = new RouteData();
        RouteData.RouteSegment seg = new RouteData.RouteSegment();
        seg.setDistance(16093.4); // 10 miles in meters
        seg.setDuration(600.0);
        routeData.setSegments(List.of(seg));

        WeatherData w1 = new WeatherData("Sunny", 72, "F", "5 mph", "NW", null, 10);
        Map<Integer, WeatherData> weather = Map.of(1, w1);

        return new ExportContext(route, routeData, weather);
    }
}
