package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.Utils;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.service.RouteService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/route")
@CrossOrigin(origins = "*")
@Slf4j
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping("/snap")
    public LocationData snapToLocation(
            @RequestParam double lat,
            @RequestParam double lon) {
        
        return routeService.snapToLocation(lat, lon);
    }

    @GetMapping("/elevation")
    public Double getElevationAtPoint(
            @RequestParam double lat,
            @RequestParam double lon) {
        
        return routeService.getElevation(lat, lon);
    }

    @PostMapping("/calculate")
    public ResponseEntity<RouteData> calculateRoute(@RequestBody List<Map<String, Object>> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
           return ResponseEntity.badRequest().build();
        }

        try {
            // Convert request waypoints to RouteService waypoints and extract durations
            List<RouteService.RouteRequest.Waypoint> routeWaypoints = new ArrayList<>();
            List<Integer> durations = new ArrayList<>();

            int i = 0;
            ZonedDateTime departureDateTime = ZonedDateTime.now(ZoneId.of(Utils.default_timezone_name));
            for (Map<String, Object> wp : waypoints) {
                Double lat = ((Number) wp.get("latitude")).doubleValue();
                Double lng = ((Number) wp.get("longitude")).doubleValue();
                String name = wp.get("name") != null ? wp.get("name").toString() : "";
                String timezoneName = (String) wp.get("timezoneName");
                routeWaypoints.add(new RouteService.RouteRequest.Waypoint(lat, lng, name, timezoneName));
                if (++i == 1) {
                   // First waypoint dictates departure time
                   try {
                      String date = (String) wp.get("date");
                      String time = (String) wp.get("time");
                      ZoneId zone = timezoneName != null && !timezoneName.isBlank() ? ZoneId.of(timezoneName) : ZoneId.of(Utils.default_timezone_name);
                      if (date != null && !date.isBlank() && time != null && !time.isBlank()) {
                         departureDateTime = Utils.getZonedDateTime(date, time, zone);
                      } else {
                         departureDateTime = ZonedDateTime.now(zone);
                      }
                   } catch (Exception e) {
                      // don't care
                   }
                }

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
