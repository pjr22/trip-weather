package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.service.RouteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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
            // Extract departure date/time if provided, or use current time as default
            String departureDateTime = null;
            if (!waypoints.isEmpty()) {
                Map<String, Object> firstWaypoint = waypoints.get(0);
                String date = firstWaypoint.get("date") != null ? firstWaypoint.get("date").toString() : "";
                String time = firstWaypoint.get("time") != null ? firstWaypoint.get("time").toString() : "";
                
                if (!date.isEmpty() && !time.isEmpty()) {
                    departureDateTime = date + " " + time;
                } else {
                    // Use current time as default departure time
                    java.time.LocalDateTime now = java.time.LocalDateTime.now();
                    java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                    departureDateTime = now.format(formatter);
                }
            }

            // Convert request waypoints to RouteService waypoints and extract durations
            List<RouteService.RouteRequest.Waypoint> routeWaypoints = new ArrayList<>();
            List<Integer> durations = new ArrayList<>();
            
            for (Map<String, Object> wp : waypoints) {
                Double lat = ((Number) wp.get("latitude")).doubleValue();
                Double lng = ((Number) wp.get("longitude")).doubleValue();
                String name = wp.get("name") != null ? wp.get("name").toString() : "";
                routeWaypoints.add(new RouteService.RouteRequest.Waypoint(lat, lng, name));
                
                // Extract duration (in minutes), default to 0 if not provided
                Integer duration = 0;
                if (wp.get("duration") != null) {
                    try {
                        duration = Integer.parseInt(wp.get("duration").toString());
                    } catch (NumberFormatException e) {
                        duration = 0;
                    }
                }
                durations.add(duration);
            }

            RouteData routeData = routeService.calculateRouteWithArrivalTimesAndDurations(routeWaypoints, departureDateTime, durations);

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
