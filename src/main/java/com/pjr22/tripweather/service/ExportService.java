package com.pjr22.tripweather.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.pjr22.tripweather.Utils;
import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.export.ExportContext;
import com.pjr22.tripweather.export.RouteExporter;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.model.WeatherData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ExportService {

    public static class ExportResult {
        private final byte[] content;
        private final String contentType;
        private final String filename;

        public ExportResult(byte[] content, String contentType, String filename) {
            this.content = content;
            this.contentType = contentType;
            this.filename = filename;
        }

        public byte[] getContent() { return content; }
        public String getContentType() { return contentType; }
        public String getFilename() { return filename; }
    }

    public static class ExportException extends RuntimeException {
        private final int status;
        public ExportException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int getStatus() { return status; }
    }

    private final RoutePersistenceService routePersistenceService;
    private final RouteService routeService;
    private final WeatherService weatherService;
    private final Map<String, RouteExporter> exporters;

    public ExportService(
            RoutePersistenceService routePersistenceService,
            RouteService routeService,
            WeatherService weatherService,
            List<RouteExporter> exporters) {
        this.routePersistenceService = routePersistenceService;
        this.routeService = routeService;
        this.weatherService = weatherService;
        this.exporters = exporters.stream()
                .collect(Collectors.toMap(RouteExporter::formatId, e -> e));
        log.info("Registered route exporters: {}", this.exporters.keySet());
    }

    public ExportResult export(UUID routeId, String format) {
        RouteExporter exporter = exporters.get(format);
        if (exporter == null) {
            throw new ExportException(501, "Export format not implemented: " + format);
        }

        RouteDto route = routePersistenceService.loadRoute(routeId);
        if (route == null) {
            throw new ExportException(404, "Route not found: " + routeId);
        }

        List<WaypointDto> waypoints = route.getWaypoints();
        if (waypoints == null || waypoints.size() < 2) {
            throw new ExportException(400, "Route must have at least 2 waypoints to export");
        }

        RouteData routeData = recomputeGeometry(route);
        if (routeData.getGeometry() == null || routeData.getGeometry().isEmpty()) {
            throw new ExportException(502, "Failed to compute route geometry");
        }

        Map<Integer, WeatherData> weather = exporter.requiresWeather()
                ? fetchWeatherPerWaypoint(waypoints)
                : Map.of();

        ExportContext context = new ExportContext(route, routeData, weather);
        byte[] content = exporter.write(context);

        String filename = sanitizeFilename(route.getName()) + "." + exporter.fileExtension();
        return new ExportResult(content, exporter.contentType(), filename);
    }

    private RouteData recomputeGeometry(RouteDto route) {
        List<WaypointDto> waypoints = route.getWaypoints();
        List<RouteService.RouteRequest.Waypoint> routeWaypoints = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();

        for (WaypointDto wp : waypoints) {
            String name = Optional.ofNullable(wp.getLocationName()).orElse("");
            routeWaypoints.add(new RouteService.RouteRequest.Waypoint(
                    wp.getLatitude(), wp.getLongitude(), name, wp.getTimezone()));
            durations.add(wp.getDurationMin() != null ? wp.getDurationMin() : 0);
        }

        ZonedDateTime departureDateTime = resolveDepartureTime(waypoints.get(0));
        return routeService.calculateRoute(routeWaypoints, departureDateTime, durations);
    }

    private ZonedDateTime resolveDepartureTime(WaypointDto first) {
        try {
            ZoneId zone = (first.getTimezone() != null && !first.getTimezone().isBlank())
                    ? ZoneId.of(first.getTimezone())
                    : ZoneId.of(Utils.default_timezone_name);
            if (first.getDate() != null && !first.getDate().isBlank()
                    && first.getTime() != null && !first.getTime().isBlank()) {
                return Utils.getZonedDateTime(first.getDate(), first.getTime(), zone);
            }
            return ZonedDateTime.now(zone);
        } catch (Exception e) {
            log.warn("Could not resolve departure time from first waypoint, using default zone now()", e);
            return ZonedDateTime.now(ZoneId.of(Utils.default_timezone_name));
        }
    }

    private Map<Integer, WeatherData> fetchWeatherPerWaypoint(List<WaypointDto> waypoints) {
        Map<Integer, WeatherData> result = new HashMap<>();
        for (WaypointDto wp : waypoints) {
            if (wp.getDate() == null || wp.getDate().isBlank()
                    || wp.getTime() == null || wp.getTime().isBlank()) {
                continue;
            }
            try {
                WeatherData data = weatherService.getWeatherForecast(
                        wp.getLatitude(), wp.getLongitude(), wp.getDate(), wp.getTime());
                if (data != null && !data.hasError()) {
                    result.put(wp.getSequence(), data);
                }
            } catch (Exception e) {
                log.warn("Weather fetch failed for waypoint sequence {} ({}): {}",
                        wp.getSequence(), wp.getLocationName(), e.getMessage());
            }
        }
        return result;
    }

    // Keeps anything that isn't a safe filename character; falls back to "route"
    // for blank/empty results so we never produce ".gpx" with no stem.
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "route";
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        // Collapse runs of underscores for readability and trim leading/trailing.
        cleaned = cleaned.replaceAll("_+", "_").replaceAll("^_|_$", "");
        return cleaned.isBlank() ? "route" : cleaned;
    }
}
