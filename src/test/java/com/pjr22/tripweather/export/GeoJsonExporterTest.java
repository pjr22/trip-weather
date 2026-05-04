package com.pjr22.tripweather.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.model.WeatherData;

class GeoJsonExporterTest {

    private final GeoJsonExporter exporter = new GeoJsonExporter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void producesValidGeoJsonStructure() throws Exception {
        JsonNode root = mapper.readTree(exporter.write(fixture()));
        assertEquals("FeatureCollection", root.get("type").asText());
        assertTrue(root.get("features").isArray());
    }

    @Test
    void containsOneLineStringAndOnePointPerWaypoint() throws Exception {
        JsonNode root = mapper.readTree(exporter.write(fixture()));
        JsonNode features = root.get("features");
        assertEquals(3, features.size(), "1 LineString + 2 Point features");
        assertEquals("LineString", features.get(0).get("geometry").get("type").asText());
        assertEquals("Point", features.get(1).get("geometry").get("type").asText());
        assertEquals("Point", features.get(2).get("geometry").get("type").asText());
    }

    @Test
    void lineStringPropertiesIncludeRouteSummary() throws Exception {
        JsonNode root = mapper.readTree(exporter.write(fixture()));
        JsonNode props = root.get("features").get(0).get("properties");
        assertEquals("Test Route", props.get("name").asText());
        assertEquals(16093.4, props.get("distanceMeters").asDouble());
        assertEquals(600.0, props.get("durationSeconds").asDouble());
    }

    @Test
    void elevationStatsComputedFromGeometry() throws Exception {
        JsonNode root = mapper.readTree(exporter.write(fixture()));
        JsonNode props = root.get("features").get(0).get("properties");
        // Geometry has 100 → 150 → 130: ascent=50, descent=20
        assertEquals(50.0, props.get("totalAscentMeters").asDouble());
        assertEquals(20.0, props.get("totalDescentMeters").asDouble());
    }

    @Test
    void waypointFeatureHasCoordinatesInLonLatEleOrder() throws Exception {
        JsonNode root = mapper.readTree(exporter.write(fixture()));
        JsonNode coords = root.get("features").get(1).get("geometry").get("coordinates");
        assertEquals(-122.6, coords.get(0).asDouble());
        assertEquals(45.5, coords.get(1).asDouble());
        assertEquals(100.0, coords.get(2).asDouble());
    }

    @Test
    void waypointWithWeatherIncludesWeatherProperties() throws Exception {
        JsonNode root = mapper.readTree(exporter.write(fixture()));
        JsonNode props = root.get("features").get(1).get("properties");
        assertEquals("Sunny", props.get("weatherCondition").asText());
        assertEquals(72, props.get("temperature").asInt());
        assertEquals("F", props.get("temperatureUnit").asText());
    }

    @Test
    void waypointWithoutWeatherOmitsWeatherProperties() throws Exception {
        JsonNode root = mapper.readTree(exporter.write(fixture()));
        JsonNode props = root.get("features").get(2).get("properties");
        assertNull(props.get("weatherCondition"));
        assertNull(props.get("temperature"));
    }

    @Test
    void waypointFeaturePropertiesIncludeArrivalTimeAndTimezone() throws Exception {
        JsonNode root = mapper.readTree(exporter.write(fixture()));
        JsonNode props = root.get("features").get(1).get("properties");
        assertEquals("2026-06-01 09:00", props.get("arrivalTime").asText());
        assertEquals("America/Los_Angeles", props.get("timezone").asText());
        assertNotNull(props.get("durationMinutes"));
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
        wp2.setElevation(130.0);
        wp2.setDate("2026-06-01");
        wp2.setTime("10:00");
        wp2.setTimezone("America/Los_Angeles");
        wp2.setDurationMin(30);

        RouteDto route = new RouteDto();
        route.setName("Test Route");
        route.setWaypoints(List.of(wp1, wp2));

        RouteData routeData = new RouteData();
        // Geometry [lon, lat, ele] — three points, elevation goes 100 → 150 → 130.
        routeData.setGeometry(List.of(
                List.of(-122.6, 45.5, 100.0),
                List.of(-122.65, 45.55, 150.0),
                List.of(-122.7, 45.6, 130.0)
        ));
        routeData.setDistance(16093.4);
        routeData.setDuration(600.0);

        WeatherData w1 = new WeatherData("Sunny", 72, "F", "5 mph", "NW", null, 10);
        Map<Integer, WeatherData> weather = Map.of(1, w1);

        return new ExportContext(route, routeData, weather);
    }
}
