package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.service.RouteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/route")
@CrossOrigin(origins = "*")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @PostMapping("/calculate")
    public ResponseEntity<RouteData> calculateRoute(@RequestBody List<Map<String, Object>> waypoints) {
        try {
            // Convert request waypoints to RouteService waypoints
            List<RouteService.RouteRequest.Waypoint> routeWaypoints = waypoints.stream()
                    .map(wp -> {
                        Double lat = ((Number) wp.get("latitude")).doubleValue();
                        Double lng = ((Number) wp.get("longitude")).doubleValue();
                        String name = wp.get("name") != null ? wp.get("name").toString() : "";
                        return new RouteService.RouteRequest.Waypoint(lat, lng, name);
                    })
                    .toList();

            RouteData routeData = routeService.calculateRoute(routeWaypoints);

            // Check if route calculation was successful
            if (routeData.getGeometry() != null && !routeData.getGeometry().isEmpty()) {
                return ResponseEntity.ok(routeData);
            } else {
                // Return empty route with error status
                return ResponseEntity.badRequest().body(routeData);
            }

        } catch (Exception e) {
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
