package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.Route;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.model.Waypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for handling route persistence operations
 */
@Service
public class RoutePersistenceService {
    
    private static final Logger logger = LoggerFactory.getLogger(RoutePersistenceService.class);
    
    /**
     * Save a route (for now just log the data and return a mock response)
     * @param routeDto Route data to save
     * @return Saved route data
     */
    public RouteDto saveRoute(RouteDto routeDto) {
        logger.info("=== SAVE ROUTE REQUEST ===");
        logger.info("Route Name: {}", routeDto.getName());
        logger.info("User ID: {}", routeDto.getUserId());
        logger.info("Created: {}", routeDto.getCreated());
        
        // Handle null userId by generating a default one
        UUID userId = routeDto.getUserId() != null ? routeDto.getUserId() : UUID.randomUUID();
        
        if (routeDto.getWaypoints() != null) {
            logger.info("Number of waypoints: {}", routeDto.getWaypoints().size());
            for (int i = 0; i < routeDto.getWaypoints().size(); i++) {
                WaypointDto waypoint = routeDto.getWaypoints().get(i);
                
                // Handle null durationMin by setting it to 0
                Integer durationMin = waypoint.getDurationMin() != null ? waypoint.getDurationMin() : 0;
                
                // Log waypoint details
                logger.info("Waypoint {}: {} ({}, {}) - {} min at {}", 
                    i + 1,
                    waypoint.getLocationName(),
                    waypoint.getLatitude(),
                    waypoint.getLongitude(),
                    durationMin,
                    waypoint.getDateTime()
                );
            }
        } else {
            logger.info("No waypoints provided");
        }
        
        // For now, just return the input with a generated ID and timestamp
        RouteDto savedRoute = new RouteDto();
        savedRoute.setId(UUID.randomUUID());
        savedRoute.setName(routeDto.getName());
        savedRoute.setCreated(ZonedDateTime.now());
        savedRoute.setUserId(userId);
        savedRoute.setWaypoints(routeDto.getWaypoints());
        
        logger.info("=== SAVE ROUTE COMPLETED ===");
        logger.info("Generated route ID: {}", savedRoute.getId());
        
        return savedRoute;
    }
    
    /**
     * Load a route by ID (for now just log the request and return a mock response)
     * @param routeId UUID of the route to load
     * @return Route data or null if not found
     */
    public RouteDto loadRoute(UUID routeId) {
        logger.info("=== LOAD ROUTE REQUEST ===");
        logger.info("Route ID requested: {}", routeId);
        
        // For now, just log the request and return null (simulating not found)
        // In a real implementation, this would query the database
        logger.info("Database query would be executed here to find route with ID: {}", routeId);
        
        // Return null for now to simulate route not found
        // TODO: Implement actual database lookup when repositories are available
        RouteDto route = null;
        
        if (route != null) {
            logger.info("Route found: {}", route.getName());
            logger.info("Route has {} waypoints", route.getWaypoints() != null ? route.getWaypoints().size() : 0);
        } else {
            logger.info("Route not found with ID: {}", routeId);
        }
        
        logger.info("=== LOAD ROUTE COMPLETED ===");
        
        return route;
    }
    
    /**
     * Convert Route entity to RouteDto
     * @param route Route entity
     * @return RouteDto
     */
    private RouteDto convertToDto(Route route) {
        RouteDto dto = new RouteDto();
        dto.setId(route.getId());
        dto.setName(route.getName());
        dto.setCreated(route.getCreated());
        dto.setUserId(route.getUser() != null ? route.getUser().getId() : null);
        
        if (route.getWaypoints() != null) {
            List<WaypointDto> waypointDtos = new ArrayList<>();
            for (Waypoint waypoint : route.getWaypoints()) {
                waypointDtos.add(convertToDto(waypoint));
            }
            dto.setWaypoints(waypointDtos);
        }
        
        return dto;
    }
    
    /**
     * Convert Waypoint entity to WaypointDto
     * @param waypoint Waypoint entity
     * @return WaypointDto
     */
    private WaypointDto convertToDto(Waypoint waypoint) {
        WaypointDto dto = new WaypointDto();
        dto.setId(waypoint.getId());
        dto.setSequence(waypoint.getSequence());
        dto.setDateTime(waypoint.getDateTime());
        dto.setDurationMin(waypoint.getDurationMin());
        dto.setLocationName(waypoint.getLocationName());
        dto.setLatitude(waypoint.getLatitude());
        dto.setLongitude(waypoint.getLongitude());
        dto.setRouteId(waypoint.getRoute() != null ? waypoint.getRoute().getId() : null);
        return dto;
    }
    
    /**
     * Convert RouteDto to Route entity
     * @param dto RouteDto
     * @return Route entity
     */
    private Route convertToEntity(RouteDto dto) {
        Route route = new Route();
        route.setId(dto.getId());
        route.setName(dto.getName());
        route.setCreated(dto.getCreated());
        
        if (dto.getUserId() != null) {
            User user = new User();
            user.setId(dto.getUserId());
            route.setUser(user);
        }
        
        // Waypoints would be set separately due to bidirectional relationship
        return route;
    }
    
    /**
     * Convert WaypointDto to Waypoint entity
     * @param dto WaypointDto
     * @return Waypoint entity
     */
    private Waypoint convertToEntity(WaypointDto dto) {
        Waypoint waypoint = new Waypoint();
        waypoint.setId(dto.getId()); // This will be null for new waypoints, @PrePersist will generate UUID
        waypoint.setSequence(dto.getSequence());
        waypoint.setDateTime(dto.getDateTime());
        waypoint.setDurationMin(dto.getDurationMin() != null ? dto.getDurationMin() : 0); // Handle null duration
        waypoint.setLocationName(dto.getLocationName());
        waypoint.setLatitude(dto.getLatitude());
        waypoint.setLongitude(dto.getLongitude());
        
        // Route would be set separately due to bidirectional relationship
        return waypoint;
    }
}
