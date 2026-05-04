package com.pjr22.tripweather.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.model.WeatherData;

@Component
public class GeoJsonExporter implements RouteExporter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String formatId() { return "geojson"; }
    @Override public String contentType() { return "application/geo+json"; }
    @Override public String fileExtension() { return "geojson"; }
    @Override public boolean requiresWeather() { return true; }

    @Override
    public byte[] write(ExportContext context) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "FeatureCollection");

        List<Map<String, Object>> features = new ArrayList<>();
        features.add(buildLineStringFeature(context));
        for (WaypointDto wp : context.getRoute().getWaypoints()) {
            features.add(buildWaypointFeature(wp, context.getWaypointWeatherBySequence().get(wp.getSequence())));
        }
        root.put("features", features);

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            // Should not happen with our hand-built map of standard types.
            throw new IllegalStateException("Failed to serialise GeoJSON", e);
        }
    }

    private Map<String, Object> buildLineStringFeature(ExportContext context) {
        RouteData routeData = context.getRouteData();

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "LineString");
        geometry.put("coordinates", routeData.getGeometry());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", context.getRoute().getName());
        if (routeData.getDistance() != null) {
            properties.put("distanceMeters", routeData.getDistance());
        }
        if (routeData.getDuration() != null) {
            properties.put("durationSeconds", routeData.getDuration());
        }
        addElevationStats(properties, routeData.getGeometry());

        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("geometry", geometry);
        feature.put("properties", properties);
        return feature;
    }

    private Map<String, Object> buildWaypointFeature(WaypointDto wp, WeatherData weather) {
        // GeoJSON coordinate order: [lon, lat, ele].
        List<Double> coordinates = new ArrayList<>();
        coordinates.add(wp.getLongitude());
        coordinates.add(wp.getLatitude());
        if (wp.getElevation() != null) {
            coordinates.add(wp.getElevation());
        }

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Point");
        geometry.put("coordinates", coordinates);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sequence", wp.getSequence());
        properties.put("name", wp.getLocationName());
        if (wp.getDate() != null && !wp.getDate().isBlank()
                && wp.getTime() != null && !wp.getTime().isBlank()) {
            properties.put("arrivalTime", wp.getDate() + " " + wp.getTime());
        }
        if (wp.getDurationMin() != null) {
            properties.put("durationMinutes", wp.getDurationMin());
        }
        if (wp.getTimezone() != null) {
            properties.put("timezone", wp.getTimezone());
        }
        if (wp.getElevation() != null) {
            properties.put("elevationMeters", wp.getElevation());
        }
        if (weather != null) {
            properties.put("weatherCondition", weather.getCondition());
            properties.put("temperature", weather.getTemperature());
            properties.put("temperatureUnit", weather.getTemperatureUnit());
            properties.put("windSpeed", weather.getWindSpeed());
            properties.put("windDirection", weather.getWindDirection());
            properties.put("precipitationProbability", weather.getPrecipitationProbability());
        }

        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("geometry", geometry);
        feature.put("properties", properties);
        return feature;
    }

    private void addElevationStats(Map<String, Object> properties, List<List<Double>> geometry) {
        if (geometry == null || geometry.size() < 2) return;
        double ascent = 0;
        double descent = 0;
        boolean hasElevation = false;
        for (int i = 1; i < geometry.size(); i++) {
            List<Double> prev = geometry.get(i - 1);
            List<Double> curr = geometry.get(i);
            if (prev.size() < 3 || curr.size() < 3) continue;
            hasElevation = true;
            double delta = curr.get(2) - prev.get(2);
            if (delta > 0) ascent += delta;
            else descent += -delta;
        }
        if (hasElevation) {
            properties.put("totalAscentMeters", ascent);
            properties.put("totalDescentMeters", descent);
        }
    }
}
