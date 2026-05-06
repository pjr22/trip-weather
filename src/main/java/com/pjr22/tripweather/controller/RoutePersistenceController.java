package com.pjr22.tripweather.controller;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.RouteSearchResultDto;
import com.pjr22.tripweather.service.RoutePersistenceService;
import com.pjr22.tripweather.service.RoutePersistenceService.RouteNotFoundException;
import com.pjr22.tripweather.service.RoutePersistenceService.RouteOwnershipException;

/**
 * REST Controller for route persistence operations
 */
@RestController
@RequestMapping(value = "/api/routes", produces = MediaType.APPLICATION_JSON_VALUE)
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

        boolean isNewRoute = (routeDto.getId() == null);
        try {
            RouteDto savedRoute = routePersistenceService.saveRoute(routeDto);
            return isNewRoute
                    ? ResponseEntity.status(201).body(savedRoute)
                    : ResponseEntity.ok(savedRoute);
        } catch (RouteOwnershipException e) {
            // Re-throw so Spring's @ResponseStatus on the exception class
            // produces the 403 with no body — keeps the controller simple.
            throw e;
        } catch (Exception e) {
            logger.error("Error saving route", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Search for routes whose name contains searchText. Scoped to the
     * current user when authenticated; falls back to the shared guest
     * user's routes when anonymous.
     */
    @GetMapping("/search/{searchText}")
    public ResponseEntity<List<RouteSearchResultDto>> searchForRoutes(@PathVariable String searchText) {
        logger.info("Searching for routes with text: {}", searchText);

        try {
            List<RouteSearchResultDto> results = routePersistenceService.searchRoutes(searchText);
            logger.info("Found {} routes matching search text: {}", results.size(), searchText);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            logger.error("Error searching for routes with text: {}", searchText, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Load a route by UUID. Open to anonymous callers — share-by-link works
     * for everyone; access control on the route itself happens elsewhere.
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

    /**
     * Delete a route. Authenticated only (enforced by SecurityConfig).
     * Returns 204 on success, 404 if the route doesn't exist or belongs
     * to a different user.
     */
    @DeleteMapping("/{routeUuid}")
    public ResponseEntity<Void> deleteRoute(@PathVariable UUID routeUuid) {
        logger.info("Received request to delete route: {}", routeUuid);
        try {
            routePersistenceService.deleteRoute(routeUuid);
            return ResponseEntity.noContent().build();
        } catch (RouteNotFoundException e) {
            // @ResponseStatus on the exception class produces 404; rethrow so
            // Spring handles the body (empty) and status correctly.
            throw e;
        }
    }
}
