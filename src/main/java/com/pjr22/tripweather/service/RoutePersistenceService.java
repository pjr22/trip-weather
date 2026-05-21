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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.RouteSummaryDto;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.FavoriteWaypoint;
import com.pjr22.tripweather.model.Route;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.model.Waypoint;
import com.pjr22.tripweather.repository.FavoriteWaypointRepository;
import com.pjr22.tripweather.repository.RouteRepository;
import com.pjr22.tripweather.security.CurrentUserService;

/**
 * Service for handling route persistence operations.
 *
 * Phase 3 makes save / search / delete user-aware: the owner is resolved from
 * the security context (falling back to the shared guest user for anonymous
 * callers), not from the request body. Routes saved while logged in are
 * private to that user; routes saved as guest stay in the shared bucket.
 */
@Service
public class RoutePersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(RoutePersistenceService.class);

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private FavoriteWaypointRepository favoriteWaypointRepository;

    /**
     * Save a route (create new or update existing). The owner is whoever is
     * currently signed in (or the shared guest user if anonymous); the
     * {@code userId} on the incoming DTO is ignored on input. Updating a
     * route owned by someone else raises {@link RouteOwnershipException}.
     */
    @Transactional
    public RouteDto saveRoute(RouteDto routeDto) {
        logger.info("=== SAVE ROUTE REQUEST ===");
        logger.info("Route Name: {}", routeDto.getName());
        logger.info("Route ID: {}", routeDto.getId());

        User user = currentUserService.currentUserOrGuest();
        logger.info("Saving as user: {} (ID: {})", user.getName(), user.getId());

        boolean isNewRoute = (routeDto.getId() == null);
        Route route;

        if (isNewRoute) {
            logger.info("Creating new route");
            route = new Route();
            route.setName(routeDto.getName());
            route.setUser(user);
            // UUID and timestamp will be set by @PrePersist
        } else {
            logger.info("Updating existing route with ID: {}", routeDto.getId());
            Optional<Route> existingRouteOpt = routeRepository.findById(routeDto.getId());
            if (existingRouteOpt.isPresent()) {
                route = existingRouteOpt.get();
                if (!route.getUser().getId().equals(user.getId())) {
                    logger.warn("Save denied — route {} owned by {}, request from {}",
                            route.getId(), route.getUser().getId(), user.getId());
                    throw new RouteOwnershipException(
                            "You don't have permission to modify this route.");
                }
                route.setName(routeDto.getName());
            } else {
                logger.warn("Route with ID {} not found, creating new route instead", routeDto.getId());
                route = new Route();
                route.setId(routeDto.getId());
                route.setName(routeDto.getName());
                route.setUser(user);
                route.setCreated(routeDto.getCreated() != null ? routeDto.getCreated() : ZonedDateTime.now());
            }
        }

        if (routeDto.getWaypoints() != null) {
            logger.info("Processing {} waypoints", routeDto.getWaypoints().size());

            if (isNewRoute) {
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
                manageWaypointsForExistingRoute(route, routeDto.getWaypoints());
            }
        } else {
            logger.info("No waypoints provided");
            route.setWaypoints(new ArrayList<>());
        }

        Route savedRoute = routeRepository.save(route);

        logger.info("=== SAVE ROUTE COMPLETED ===");
        logger.info("Route saved with ID: {} (new: {})", savedRoute.getId(), isNewRoute);

        return convertToDto(savedRoute);
    }

    /**
     * Manage waypoints for an existing route
     * @param route Existing route entity
     * @param waypointDtos Waypoint DTOs from the request
     */
    private void manageWaypointsForExistingRoute(Route route, List<WaypointDto> waypointDtos) {
        if (route.getWaypoints() == null) {
            route.setWaypoints(new ArrayList<>());
        }

        Map<UUID, Waypoint> existingWaypointsMap = new HashMap<>();
        for (Waypoint wp : route.getWaypoints()) {
            if (wp.getId() != null) {
                existingWaypointsMap.put(wp.getId(), wp);
            }
        }

        Map<UUID, WaypointDto> requestWaypointsMap = new HashMap<>();
        for (WaypointDto dto : waypointDtos) {
            if (dto.getId() != null) {
                requestWaypointsMap.put(dto.getId(), dto);
            }
        }

        route.getWaypoints().clear();

        for (int i = 0; i < waypointDtos.size(); i++) {
            WaypointDto waypointDto = waypointDtos.get(i);

            if (waypointDto.getId() != null && existingWaypointsMap.containsKey(waypointDto.getId())) {
                Waypoint existingWaypoint = existingWaypointsMap.get(waypointDto.getId());
                updateWaypointFromDto(existingWaypoint, waypointDto);
                existingWaypoint.setSequence(i + 1);
                route.getWaypoints().add(existingWaypoint);
                logger.debug("Updated existing waypoint {}: {}", waypointDto.getId(), existingWaypoint.getLocationName());
            } else {
                Waypoint newWaypoint = convertToEntity(waypointDto);
                newWaypoint.setSequence(i + 1);
                newWaypoint.setRoute(route);
                route.getWaypoints().add(newWaypoint);
                logger.debug("Created new waypoint: {}", newWaypoint.getLocationName());
            }
        }

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
            populateFavoriteIds(routeDto);
            logger.info("=== LOAD ROUTE COMPLETED ===");
            return routeDto;
        } else {
            logger.info("Route not found with ID: {}", routeId);
            logger.info("=== LOAD ROUTE COMPLETED ===");
            return null;
        }
    }

    /**
     * Delete a route owned by the current user. Routes that don't exist or
     * belong to another user surface as 404 (not 403) so the response doesn't
     * leak the existence of someone else's route.
     */
    @Transactional
    public void deleteRoute(UUID routeId) {
        logger.info("=== DELETE ROUTE REQUEST ===");
        logger.info("Route ID: {}", routeId);

        User user = currentUserService.currentUser()
                .orElseThrow(() -> new RouteNotFoundException("Route not found"));

        Optional<Route> routeOpt = routeRepository.findById(routeId);
        if (routeOpt.isEmpty() || !routeOpt.get().getUser().getId().equals(user.getId())) {
            // 404 either way — don't reveal whether a non-owned route exists.
            throw new RouteNotFoundException("Route not found");
        }

        routeRepository.delete(routeOpt.get());
        logger.info("Route {} deleted by user {}", routeId, user.getId());
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
            List<Waypoint> sortedWaypoints = route.getWaypoints().stream()
                .sorted((w1, w2) -> Integer.compare(w1.getSequence(), w2.getSequence()))
                .toList();
            for (Waypoint waypoint : sortedWaypoints) {
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
        dto.setDate(waypoint.getDate());
        dto.setTime(waypoint.getTime());
        dto.setTimezone(waypoint.getTimezone());
        dto.setDurationMin(waypoint.getDurationMin());
        dto.setLocationName(waypoint.getLocationName());
        dto.setLatitude(waypoint.getLatitude());
        dto.setElevation(waypoint.getElevation());
        dto.setLongitude(waypoint.getLongitude());
        dto.setRouteId(waypoint.getRoute() != null ? waypoint.getRoute().getId() : null);
        return dto;
    }

    /**
     * Convert WaypointDto to Waypoint entity
     * @param dto WaypointDto
     * @return Waypoint entity
     */
    private Waypoint convertToEntity(WaypointDto dto) {
        Waypoint waypoint = new Waypoint();
        waypoint.setId(dto.getId()); // null for new waypoints, @PrePersist will generate UUID
        waypoint.setSequence(dto.getSequence());
        waypoint.setDate(dto.getDate());
        waypoint.setTime(dto.getTime());
        waypoint.setTimezone(dto.getTimezone());
        waypoint.setDurationMin(dto.getDurationMin() != null ? dto.getDurationMin() : 0);
        waypoint.setLocationName(dto.getLocationName());
        waypoint.setLatitude(dto.getLatitude());
        waypoint.setLongitude(dto.getLongitude());
        waypoint.setElevation(dto.getElevation());
        return waypoint;
    }

    /**
     * Update existing Waypoint entity from WaypointDto
     * @param waypoint Existing Waypoint entity to update
     * @param dto WaypointDto with new data
     */
    private void updateWaypointFromDto(Waypoint waypoint, WaypointDto dto) {
        waypoint.setSequence(dto.getSequence());
        waypoint.setDate(dto.getDate());
        waypoint.setTime(dto.getTime());
        waypoint.setTimezone(dto.getTimezone());
        waypoint.setDurationMin(dto.getDurationMin() != null ? dto.getDurationMin() : 0);
        waypoint.setLocationName(dto.getLocationName());
        waypoint.setLatitude(dto.getLatitude());
        waypoint.setLongitude(dto.getLongitude());
        // Note: ID and Route are not updated as they should remain the same
    }

    /**
     * Patch each waypoint DTO with the viewer's matching favorite id, if any.
     * Anonymous viewers leave every {@code favoriteId} null. The match uses
     * the same tiered proximity rule as the heart-toggle /check endpoint —
     * see {@link FavoriteWaypointService#matchByProximity}. Keeping the two
     * paths on one matcher means a place looks "favorited" identically
     * whether the user clicks a fresh waypoint or loads a saved route.
     *
     * <p>Implementation note: one bulk fetch of the viewer's favorites + an
     * in-memory scan per waypoint. Favorites-per-user is bounded (tens in
     * practice) and waypoint counts are similarly small, so the N×M
     * comparison is cheap compared to a single round-trip.
     */
    private void populateFavoriteIds(RouteDto routeDto) {
        if (routeDto == null || routeDto.getWaypoints() == null || routeDto.getWaypoints().isEmpty()) {
            return;
        }
        Optional<User> viewerOpt = currentUserService.currentUser();
        if (viewerOpt.isEmpty()) {
            return;
        }
        UUID viewerId = viewerOpt.get().getId();
        List<FavoriteWaypoint> favorites = favoriteWaypointRepository.findAllByUser(viewerId);
        if (favorites.isEmpty()) {
            return;
        }
        for (WaypointDto w : routeDto.getWaypoints()) {
            if (w.getLatitude() == null || w.getLongitude() == null) continue;
            FavoriteWaypointService.matchByProximity(
                            favorites, w.getLatitude(), w.getLongitude(), w.getLocationName())
                    .ifPresent(match -> w.setFavoriteId(match.getId()));
        }
    }

    /**
     * List the caller's routes as {@link RouteSummaryDto} summaries, sorted
     * by {@code created DESC}. Phase 4 of FAVORITES_AND_ROUTE_MGMT.md —
     * replaces the legacy {@code searchRoutes} / {@code RouteSearchResultDto}
     * shape with a per-row summary that includes the waypoint count.
     *
     * <p>Scope: authenticated → own routes; anonymous → the shared guest
     * user's routes (the existing public-bucket behaviour). Optional
     * substring filter on {@code name} (case-insensitive); blank/null →
     * unfiltered.
     */
    @Transactional(readOnly = true)
    public List<RouteSummaryDto> listRoutes(String searchOrNull) {
        User user = currentUserService.currentUserOrGuest();
        boolean filtered = (searchOrNull != null && !searchOrNull.isBlank());
        return filtered
                ? routeRepository.searchSummariesByUser(user.getId(), searchOrNull.trim())
                : routeRepository.findSummariesByUser(user.getId());
    }

    /**
     * Rename a single route owned by the current user. Phase 4 of
     * FAVORITES_AND_ROUTE_MGMT.md — backs {@code PATCH /api/routes/{id}}.
     *
     * <p>Validates the new name (non-blank, length ≤ 255) and applies the
     * same 404-on-non-owned posture as {@link #deleteRoute(UUID)} so the
     * response can't be used to enumerate other users' routes. Anonymous
     * callers are blocked by the security chain.
     */
    @Transactional
    public RouteSummaryDto renameRoute(UUID routeId, String newName) {
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidRouteException("name is required");
        }
        if (trimmed.length() > 255) {
            throw new InvalidRouteException("name is too long (max 255 characters)");
        }

        User user = currentUserService.currentUser()
                .orElseThrow(() -> new RouteNotFoundException("Route not found"));

        Route route = routeRepository.findById(routeId)
                .filter(r -> r.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new RouteNotFoundException("Route not found"));

        route.setName(trimmed);
        Route saved = routeRepository.save(route);
        logger.info("Route {} renamed by user {}", saved.getId(), user.getId());

        long waypointCount = saved.getWaypoints() == null ? 0L : saved.getWaypoints().size();
        return new RouteSummaryDto(saved.getId(), saved.getName(), saved.getCreated(), waypointCount);
    }

    /**
     * Authenticated user attempted to modify a route they don't own.
     * Spring maps {@code @ResponseStatus} to a 403 automatically.
     */
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public static class RouteOwnershipException extends RuntimeException {
        public RouteOwnershipException(String message) { super(message); }
    }

    /**
     * Used for "this route doesn't exist for the current user" — the same
     * status whether the route truly doesn't exist or belongs to someone
     * else, so the response can't be used to enumerate other users' routes.
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class RouteNotFoundException extends RuntimeException {
        public RouteNotFoundException(String message) { super(message); }
    }

    /**
     * 400 — the rename request body is missing a name or violates the
     * length cap. Same shape as {@code FavoriteWaypointService.InvalidFavoriteException}.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidRouteException extends RuntimeException {
        public InvalidRouteException(String message) { super(message); }
    }
}
