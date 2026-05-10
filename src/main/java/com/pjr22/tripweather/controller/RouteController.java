package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.Utils;
import com.pjr22.tripweather.dto.LocationResolution;
import com.pjr22.tripweather.dto.RouteCalculateRequest;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.service.RouteService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;

// No @CrossOrigin: the SPA is served from the same origin as this API. If the frontend
// is ever hosted on a different origin (e.g. a separate dev server), add a CorsFilter /
// WebMvcConfigurer driven by an allowlist env var instead of re-adding @CrossOrigin here.
@RestController
@RequestMapping("/api/route")
@Validated
@Slf4j
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    /**
     * Resolves a lat/lon to a navigation-ready point: snaps to the road
     * network, returns elevation, and reports whether the input is routable.
     *
     * <p>The endpoint kept its {@code /elevation} path for continuity, but
     * now returns a {@link LocationResolution} rather than a raw elevation
     * number. Search-result and GPS flows in the frontend use the snapped
     * point's lat/lon as the navigation waypoint and read its elevation
     * directly. Map clicks use the {@code routable} flag to gate waypoint
     * creation.
     */
    @GetMapping("/elevation")
    public LocationResolution resolveLocation(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon) {

        return routeService.resolveLocation(lat, lon);
    }

    @PostMapping("/calculate")
    public ResponseEntity<RouteData> calculateRoute(@Valid @RequestBody RouteCalculateRequest request) {
        try {
            List<RouteService.RouteRequest.Waypoint> routeWaypoints = new ArrayList<>();
            List<Integer> durations = new ArrayList<>();

            ZonedDateTime departureDateTime = ZonedDateTime.now(ZoneId.of(Utils.default_timezone_name));
            List<RouteCalculateRequest.WaypointInput> waypoints = request.getWaypoints();
            for (int i = 0; i < waypoints.size(); i++) {
                RouteCalculateRequest.WaypointInput wp = waypoints.get(i);
                String name = wp.getName() != null ? wp.getName() : "";
                String timezoneName = wp.getTimezoneName();
                routeWaypoints.add(new RouteService.RouteRequest.Waypoint(
                        wp.getLatitude(), wp.getLongitude(), name, timezoneName));

                if (i == 0) {
                    // First waypoint dictates departure time
                    try {
                        ZoneId zone = timezoneName != null && !timezoneName.isBlank()
                                ? ZoneId.of(timezoneName) : ZoneId.of(Utils.default_timezone_name);
                        if (wp.getDate() != null && !wp.getDate().isBlank()
                                && wp.getTime() != null && !wp.getTime().isBlank()) {
                            departureDateTime = Utils.getZonedDateTime(wp.getDate(), wp.getTime(), zone);
                        } else {
                            departureDateTime = ZonedDateTime.now(zone);
                        }
                    } catch (Exception e) {
                        // fall back to default departure time
                    }
                }

                durations.add(wp.getDuration() != null ? wp.getDuration() : 0);
            }

            RouteData routeData = routeService.calculateRoute(routeWaypoints, departureDateTime, durations);

            // Check if route calculation was successful
            if (routeData.getGeometry() != null && !routeData.getGeometry().isEmpty()) {
                return ResponseEntity.ok(routeData);
            } else {
                // Return empty route with error status
                return ResponseEntity.badRequest().body(routeData);
            }

        } catch (Exception e) {
            log.error("Route calculation failed.", e);
            // Create empty route data for error case
            RouteData errorRoute = new RouteData();
            errorRoute.setDistance(0.0);
            errorRoute.setDuration(0.0);
            return ResponseEntity.badRequest().body(errorRoute);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Route service is healthy");
    }
}
