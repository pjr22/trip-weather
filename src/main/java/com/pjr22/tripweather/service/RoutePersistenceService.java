package com.pjr22.tripweather.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.Route;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.model.Waypoint;
import com.pjr22.tripweather.repository.RouteRepository;

/**
 * Service for handling route persistence operations
 */
@Service
public class RoutePersistenceService {
    
    private static final Logger logger = LoggerFactory.getLogger(RoutePersistenceService.class);
    
    @Autowired
    private RouteRepository routeRepository;
    
    @Autowired
    private UserManagementService userManagementService;
    
    /**
     * Save a route (create new or update existing)
     * @param routeDto Route data to save
     * @return Saved route data
     */
    @Transactional
    public RouteDto saveRoute(RouteDto routeDto) {
        logger.info("=== SAVE ROUTE REQUEST ===");
        logger.info("Route Name: {}", routeDto.getName());
        logger.info("Route ID: {}", routeDto.getId());
        logger.info("User ID: {}", routeDto.getUserId());
        
        // Get the user (guest if userId is null or user not found)
        User user = userManagementService.getUserByIdOrGuest(routeDto.getUserId());
        logger.info("Using user: {} (ID: {})", user.getName(), user.getId());
        
        // Determine if this is a new route or an update
        boolean isNewRoute = (routeDto.getId() == null);
        Route route;
        
        if (isNewRoute) {
            // Create new route
            logger.info("Creating new route");
            route = new Route();
            route.setName(routeDto.getName());
            route.setUser(user);
            // UUID and timestamp will be set by @PrePersist
        } else {
            // Update existing route
            logger.info("Updating existing route with ID: {}", routeDto.getId());
            Optional<Route> existingRouteOpt = routeRepository.findById(routeDto.getId());
            if (existingRouteOpt.isPresent()) {
                route = existingRouteOpt.get();
                
                // Eagerly fetch waypoints to avoid lazy loading issues
                if (route.getWaypoints() != null) {
                    route.getWaypoints().size(); // Force initialization
                }
                
                route.setName(routeDto.getName());
                // Verify the route belongs to the user (security check)
                if (!route.getUser().getId().equals(user.getId())) {
                    logger.warn("Route ID {} belongs to user {}, but requested by user {}. Using guest user.", 
                        route.getId(), route.getUser().getId(), user.getId());
                    user = userManagementService.getOrCreateGuestUser();
                    route.setUser(user);
                }
            } else {
                logger.warn("Route with ID {} not found, creating new route instead", routeDto.getId());
                route = new Route();
                route.setId(routeDto.getId()); // Use the requested ID
                route.setName(routeDto.getName());
                route.setUser(user);
                route.setCreated(routeDto.getCreated() != null ? routeDto.getCreated() : ZonedDateTime.now());
            }
        }
        
        // Handle waypoints using the proper entity management approach
        if (routeDto.getWaypoints() != null) {
            logger.info("Processing {} waypoints", routeDto.getWaypoints().size());
            
            if (isNewRoute) {
                // For new routes, create all waypoints
                List<Waypoint> waypoints = new ArrayList<>();
                for (int i = 0; i < routeDto.getWaypoints().size(); i++) {
                    WaypointDto waypointDto = routeDto.getWaypoints().get(i);
                    Waypoint waypoint = convertToEntity(waypointDto);
                    waypoint.setSequence(i + 1);
                    waypoint.setRoute(route);
                    waypoints.add(waypoint);
                    logger.debug("Created new waypoint {}: {}", i + 1, waypoint.getLocationName());
                }
                route.setWaypoints(waypoints);
            } else {
                // For existing routes, manage waypoints properly
                manageWaypointsForExistingRoute(route, routeDto.getWaypoints());
            }
        } else {
            logger.info("No waypoints provided");
            route.setWaypoints(new ArrayList<>());
        }
        
        // Save the route (this will also save waypoints due to cascade)
        Route savedRoute = routeRepository.save(route);
        
        logger.info("=== SAVE ROUTE COMPLETED ===");
        logger.info("Route saved with ID: {}", savedRoute.getId());
        logger.info("Is new route: {}", isNewRoute);
        
        return convertToDto(savedRoute);
    }
    
    /**
     * Manage waypoints for an existing route
     * @param route Existing route entity
     * @param waypointDtos Waypoint DTOs from the request
     */
    private void manageWaypointsForExistingRoute(Route route, List<WaypointDto> waypointDtos) {
        // Initialize waypoints collection if null
        if (route.getWaypoints() == null) {
            route.setWaypoints(new ArrayList<>());
        }
        
        // Create a map of existing waypoints by ID for efficient lookup
        Map<UUID, Waypoint> existingWaypointsMap = new HashMap<>();
        for (Waypoint wp : route.getWaypoints()) {
            if (wp.getId() != null) {
                existingWaypointsMap.put(wp.getId(), wp);
            }
        }
        
        // Create a map of request waypoints by ID for efficient lookup
        Map<UUID, WaypointDto> requestWaypointsMap = new HashMap<>();
        for (WaypointDto dto : waypointDtos) {
            if (dto.getId() != null) {
                requestWaypointsMap.put(dto.getId(), dto);
            }
        }
        
        // Clear the existing collection first to avoid duplicates
        route.getWaypoints().clear();
        
        // Process each waypoint in the request and add to the same collection
        for (int i = 0; i < waypointDtos.size(); i++) {
            WaypointDto waypointDto = waypointDtos.get(i);
            
            if (waypointDto.getId() != null && existingWaypointsMap.containsKey(waypointDto.getId())) {
                // Update existing waypoint
                Waypoint existingWaypoint = existingWaypointsMap.get(waypointDto.getId());
                updateWaypointFromDto(existingWaypoint, waypointDto);
                existingWaypoint.setSequence(i + 1);
                route.getWaypoints().add(existingWaypoint);
                logger.debug("Updated existing waypoint {}: {}", waypointDto.getId(), existingWaypoint.getLocationName());
            } else {
                // Create new waypoint
                Waypoint newWaypoint = convertToEntity(waypointDto);
                newWaypoint.setSequence(i + 1);
                newWaypoint.setRoute(route);
                route.getWaypoints().add(newWaypoint);
                logger.debug("Created new waypoint: {}", newWaypoint.getLocationName());
            }
        }
        
        // Log the final state for debugging
        logger.info("Route now has {} waypoints after update", route.getWaypoints().size());
    }
    
    /**
     * Load a route by ID
     * @param routeId UUID of the route to load
     * @return Route data or null if not found
     */
    @Transactional(readOnly = true)
    public RouteDto loadRoute(UUID routeId) {
        logger.info("=== LOAD ROUTE REQUEST ===");
        logger.info("Route ID requested: {}", routeId);
        
        Optional<Route> routeOpt = routeRepository.findById(routeId);
        
        if (routeOpt.isPresent()) {
            Route route = routeOpt.get();
            logger.info("Route found: {}", route.getName());
            logger.info("Route belongs to user: {} (ID: {})", route.getUser().getName(), route.getUser().getId());
            
            if (route.getWaypoints() != null) {
                logger.info("Route has {} waypoints", route.getWaypoints().size());
            } else {
                logger.info("Route has no waypoints");
            }
            
            RouteDto routeDto = convertToDto(route);
            logger.info("=== LOAD ROUTE COMPLETED ===");
            return routeDto;
        } else {
            logger.info("Route not found with ID: {}", routeId);
            logger.info("=== LOAD ROUTE COMPLETED ===");
            return null;
        }
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
    
    /**
     * Update existing Waypoint entity from WaypointDto
     * @param waypoint Existing Waypoint entity to update
     * @param dto WaypointDto with new data
     */
    private void updateWaypointFromDto(Waypoint waypoint, WaypointDto dto) {
        waypoint.setSequence(dto.getSequence());
        waypoint.setDateTime(dto.getDateTime());
        waypoint.setDurationMin(dto.getDurationMin() != null ? dto.getDurationMin() : 0); // Handle null duration
        waypoint.setLocationName(dto.getLocationName());
        waypoint.setLatitude(dto.getLatitude());
        waypoint.setLongitude(dto.getLongitude());
        // Note: ID and Route are not updated as they should remain the same
    }
}
