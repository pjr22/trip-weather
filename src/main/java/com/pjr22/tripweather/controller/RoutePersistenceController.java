package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.service.RoutePersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for route persistence operations
 */
@RestController
@RequestMapping("/api/routes")
public class RoutePersistenceController {
    
    private static final Logger logger = LoggerFactory.getLogger(RoutePersistenceController.class);
    
    @Autowired
    private RoutePersistenceService routePersistenceService;
    
    /**
     * Save a route
     * @param routeDto Route data to save
     * @return Saved route data
     */
    @PostMapping
    public ResponseEntity<RouteDto> saveRoute(@RequestBody RouteDto routeDto) {
        logger.info("Received request to save route: {}", routeDto.getName());
        logger.info("Route contains {} waypoints", routeDto.getWaypoints() != null ? routeDto.getWaypoints().size() : 0);
        
        try {
            RouteDto savedRoute = routePersistenceService.saveRoute(routeDto);
            logger.info("Successfully saved route with ID: {}", savedRoute.getId());
            return ResponseEntity.ok(savedRoute);
        } catch (Exception e) {
            logger.error("Error saving route", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Load a route by ID
     * @param routeId UUID of the route to load
     * @return Route data
     */
    @GetMapping("/{routeId}")
    public ResponseEntity<RouteDto> loadRoute(@PathVariable String routeId) {
       
        logger.info("Received request to load route with ID: {}", routeId);
        
        try {
            UUID routeUuid = UUID.fromString(routeId);
            RouteDto route = routePersistenceService.loadRoute(routeUuid);
            if (route != null) {
                logger.info("Successfully loaded route: {}", route.getName());
                logger.info("Route contains {} waypoints", route.getWaypoints() != null ? route.getWaypoints().size() : 0);
                return ResponseEntity.ok(route);
            } else {
                logger.warn("Route not found with ID: {}", routeId);
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            logger.error("Error loading route", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
