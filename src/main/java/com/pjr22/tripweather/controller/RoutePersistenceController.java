package com.pjr22.tripweather.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.RouteSearchResultDto;
import com.pjr22.tripweather.service.RoutePersistenceService;

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
     * @return Saved route data with appropriate HTTP status
     */
    @PostMapping
    public ResponseEntity<RouteDto> saveRoute(@RequestBody RouteDto routeDto) {
        logger.info("Received request to save route: {}", routeDto.getName());
        
        try {
            // Check if this is a new route (null ID) or an update
            boolean isNewRoute = (routeDto.getId() == null);
            
            RouteDto savedRoute = routePersistenceService.saveRoute(routeDto);
            
            // Return 201 Created for new routes, 200 OK for updates
            if (isNewRoute) {
                return ResponseEntity.status(201).body(savedRoute);
            } else {
                return ResponseEntity.ok(savedRoute);
            }
        } catch (Exception e) {
            logger.error("Error saving route", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Search for routes with a name matching the searchText
     * 
     * @param searchText
     * @return
     */
    @GetMapping("/search/{searchText}")
    public ResponseEntity<List<RouteSearchResultDto>> searchForRoutes(@PathVariable String searchText) {
       logger.info("Searching for routes with text: {}", searchText);
       
       try {
           List<RouteSearchResultDto> results = routePersistenceService.searchRoutes(searchText, null);
           logger.info("Found {} routes matching search text: {}", results.size(), searchText);
           return ResponseEntity.ok(results);
       } catch (Exception e) {
           logger.error("Error searching for routes with text: {}", searchText, e);
           return ResponseEntity.internalServerError().build();
       }
    }

    /**
     * Load a route by UUID
     * @param routeUuid UUID of the route to load
     * @return Route data
     */
    @GetMapping("/{routeUuid}")
    public ResponseEntity<RouteDto> loadRoute(@PathVariable UUID routeUuid) {
               
        try {
            RouteDto route = routePersistenceService.loadRoute(routeUuid);
            if (route != null) {
                logger.info("Successfully loaded route: {}", route.getName());
                return ResponseEntity.ok(route);
            } else {
                logger.warn("Route not found with ID: {}", routeUuid);
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            logger.error("Error loading route", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
