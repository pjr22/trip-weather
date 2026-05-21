package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.AdminFavoritePage;
import com.pjr22.tripweather.service.AdminFavoriteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Admin favorite-management endpoints. Authorisation is the
 * {@code adminSecurityChain} in {@link com.pjr22.tripweather.config.SecurityConfig}
 * — every endpoint here requires {@code ROLE_ADMIN}. Phase 5 of
 * FAVORITES_AND_ROUTE_MGMT.md.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/admin/favorites} — paginated list with search /
 *       deletion-state filters. No owner-kind filter (favorites are an
 *       account-only feature; guest users don't own favorites).</li>
 *   <li>{@code POST   /api/admin/favorites/{id}/soft-delete} — sets
 *       {@code deleted_at = now()}. Distinct from DELETE per the plan so
 *       the HTTP method conveys the durability of the action: POST for a
 *       state change, DELETE for a permanent purge.</li>
 *   <li>{@code POST   /api/admin/favorites/{id}/restore} — clears
 *       {@code deleted_at}. Idempotent.</li>
 *   <li>{@code DELETE /api/admin/favorites/{id}} — hard delete (purge).
 *       Bypasses the soft-delete grace window — used when the operator
 *       wants to reclaim a row immediately.</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class AdminFavoriteController {

    private final AdminFavoriteService adminFavoriteService;

    public AdminFavoriteController(AdminFavoriteService adminFavoriteService) {
        this.adminFavoriteService = adminFavoriteService;
    }

    @GetMapping("/favorites")
    public AdminFavoritePage list(
            @RequestParam(name = "q",       required = false) String q,
            @RequestParam(name = "deleted", required = false, defaultValue = "false") String deleted,
            @RequestParam(name = "page",    required = false, defaultValue = "0") int page,
            @RequestParam(name = "size",    required = false, defaultValue = "25") int size,
            @RequestParam(name = "sort",    required = false, defaultValue = "created,desc") String sort) {
        return adminFavoriteService.list(q, deleted, page, size, sort);
    }

    @PostMapping("/favorites/{id}/soft-delete")
    public ResponseEntity<Void> softDelete(@PathVariable UUID id) {
        if (!adminFavoriteService.softDelete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Favorite " + id + " not found or already deleted");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/favorites/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
        if (!adminFavoriteService.restore(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Favorite " + id + " not found or not currently deleted");
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/favorites/{id}")
    public ResponseEntity<Void> hardDelete(@PathVariable UUID id) {
        if (!adminFavoriteService.hardDelete(id)) {
            // Row truly doesn't exist (active, soft-deleted, or otherwise).
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Favorite " + id + " not found");
        }
        return ResponseEntity.noContent().build();
    }
}
