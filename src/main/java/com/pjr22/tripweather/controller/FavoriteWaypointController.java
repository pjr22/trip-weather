package com.pjr22.tripweather.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pjr22.tripweather.dto.CreateFavoriteRequest;
import com.pjr22.tripweather.dto.FavoriteWaypointDto;
import com.pjr22.tripweather.dto.RenameFavoriteRequest;
import com.pjr22.tripweather.service.FavoriteWaypointService;

/**
 * REST controller for favorite-waypoint CRUD. Phase 1 of
 * FAVORITES_AND_ROUTE_MGMT.md.
 *
 * <p>Every endpoint requires authentication; SecurityConfig blocks anonymous
 * callers at {@code /api/favorites/**} so a 401 surfaces before any controller
 * method runs. Ownership / not-found / duplicate-label / validation are mapped
 * via {@code @ResponseStatus} on the exception classes in
 * {@link FavoriteWaypointService} — the controller doesn't catch them.
 */
@RestController
@RequestMapping(value = "/api/favorites", produces = MediaType.APPLICATION_JSON_VALUE)
public class FavoriteWaypointController {

    private static final Logger logger = LoggerFactory.getLogger(FavoriteWaypointController.class);

    private final FavoriteWaypointService favoriteService;

    public FavoriteWaypointController(FavoriteWaypointService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /**
     * List the caller's favorites. Optional {@code ?search=} filters by
     * label-or-locationName case-insensitive substring; missing / blank
     * returns the full alphabetical list.
     */
    @GetMapping
    public List<FavoriteWaypointDto> list(@RequestParam(name = "search", required = false) String search) {
        return favoriteService.listForCurrentUser(search);
    }

    /**
     * Existence check for the heart-toggle initial state on a fresh map-click.
     * Returns {@code 200 + body} when the place is already a favorite, or
     * {@code 204 No Content} when it isn't. 204 (not 404) so the client can
     * distinguish "not starred" from a real error — 404 is reserved for
     * ownership / id-not-found cases on the other paths.
     */
    @GetMapping("/check")
    public ResponseEntity<FavoriteWaypointDto> check(@RequestParam("lat") double latitude,
                                                     @RequestParam("lon") double longitude,
                                                     @RequestParam(name = "locationName", required = false, defaultValue = "")
                                                             String locationName) {
        Optional<FavoriteWaypointDto> hit = favoriteService.findAt(latitude, longitude, locationName);
        return hit.map(ResponseEntity::ok)
                  .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Create a new favorite. Returns 201 on success, 409 on duplicate label,
     * 400 on invalid input (handled by {@code @ResponseStatus} on the
     * exception classes).
     */
    @PostMapping
    public ResponseEntity<FavoriteWaypointDto> create(@RequestBody CreateFavoriteRequest request) {
        logger.info("Create favorite request: label='{}'", request == null ? null : request.label());
        FavoriteWaypointDto saved = favoriteService.create(request);
        return ResponseEntity.status(201).body(saved);
    }

    /**
     * Rename a favorite. Returns 200 on success, 404 on non-owned, 409 on
     * duplicate label.
     */
    @PutMapping("/{id}")
    public FavoriteWaypointDto rename(@PathVariable UUID id,
                                      @RequestBody RenameFavoriteRequest request) {
        logger.info("Rename favorite {} -> '{}'", id, request == null ? null : request.label());
        return favoriteService.rename(id, request);
    }

    /**
     * Soft-delete a favorite. Returns 204 on success, 404 on non-owned.
     * Recoverable from the admin console for the grace window (Phase 5).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        logger.info("Delete favorite {}", id);
        favoriteService.softDelete(id);
        return ResponseEntity.noContent().build();
    }
}
