package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.AdminRoutePage;
import com.pjr22.tripweather.service.AdminRouteService;
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
 * Admin route-management endpoints. Authorisation is the
 * {@code adminSecurityChain} in {@link com.pjr22.tripweather.config.SecurityConfig}
 * — every endpoint here requires {@code ROLE_ADMIN}. Phase 1 of ADMIN_CONSOLE.md.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/admin/routes} — paginated list with search /
 *       owner-kind / deletion-state filters.</li>
 *   <li>{@code DELETE /api/admin/routes/{id}} — soft-delete (sets
 *       {@code deleted_at = now()}).</li>
 *   <li>{@code POST   /api/admin/routes/{id}/restore} — clears {@code deleted_at}.</li>
 * </ul>
 *
 * <p>The cleanup-trigger endpoint moved to
 * {@link com.pjr22.tripweather.controller.AdminLoaderController} as
 * {@code POST /api/admin/loaders/guest-route-cleanup/trigger} in Phase 2 of
 * ADMIN_CONSOLE.md, alongside the EV loader and ORS coverage triggers.
 */
@RestController
@RequestMapping(value = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class AdminRouteController {

    private final AdminRouteService adminRouteService;

    public AdminRouteController(AdminRouteService adminRouteService) {
        this.adminRouteService = adminRouteService;
    }

    @GetMapping("/routes")
    public AdminRoutePage list(
            @RequestParam(name = "q",       required = false) String q,
            @RequestParam(name = "owner",   required = false) String owner,
            @RequestParam(name = "deleted", required = false, defaultValue = "false") String deleted,
            @RequestParam(name = "page",    required = false, defaultValue = "0") int page,
            @RequestParam(name = "size",    required = false, defaultValue = "25") int size,
            @RequestParam(name = "sort",    required = false, defaultValue = "created,desc") String sort) {
        return adminRouteService.list(q, owner, deleted, page, size, sort);
    }

    @DeleteMapping("/routes/{id}")
    public ResponseEntity<Void> softDelete(@PathVariable UUID id) {
        if (!adminRouteService.softDelete(id)) {
            // Either the id doesn't exist or it's already soft-deleted.
            // 404 is the cleanest signal to the SPA — the row's not in
            // the "active" set the admin just acted on.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Route " + id + " not found or already deleted");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/routes/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
        if (!adminRouteService.restore(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Route " + id + " not found or not currently deleted");
        }
        return ResponseEntity.noContent().build();
    }
}
