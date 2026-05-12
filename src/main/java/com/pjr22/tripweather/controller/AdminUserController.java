package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.AdminUserDeleteResult;
import com.pjr22.tripweather.dto.AdminUserPage;
import com.pjr22.tripweather.service.AdminUserService;
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
 * Admin user-management endpoints. Authorisation is the
 * {@code adminSecurityChain} in {@link com.pjr22.tripweather.config.SecurityConfig}
 * — every endpoint here requires {@code ROLE_ADMIN}. Phase 4 of ADMIN_CONSOLE.md.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/admin/users} — paginated list with search +
 *       enabled-filter + sort.</li>
 *   <li>{@code POST   /api/admin/users/{id}/enable} — flips {@code enabled=true}.</li>
 *   <li>{@code POST   /api/admin/users/{id}/disable} — flips {@code enabled=false}.</li>
 *   <li>{@code POST   /api/admin/users/{id}/force-verify} — enables the user and
 *       consumes every still-open verification AND password-reset token.</li>
 *   <li>{@code DELETE /api/admin/users/{id}} — hard delete; cascades routes /
 *       verifications / resets via existing FK CASCADE. Response carries the
 *       count of cascaded routes (split active vs soft-deleted) so the SPA
 *       can surface what went with the user.</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/users")
    public AdminUserPage list(
            @RequestParam(name = "q",       required = false) String q,
            @RequestParam(name = "enabled", required = false) String enabled,
            @RequestParam(name = "page",    required = false, defaultValue = "0") int page,
            @RequestParam(name = "size",    required = false, defaultValue = "25") int size,
            @RequestParam(name = "sort",    required = false, defaultValue = "created,desc") String sort) {
        return adminUserService.list(q, enabled, page, size, sort);
    }

    @PostMapping("/users/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable UUID id) {
        // Already-enabled is idempotent success (204), missing user is 404.
        return mapSetEnabledOutcome(adminUserService.setEnabled(id, true), id);
    }

    @PostMapping("/users/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        return mapSetEnabledOutcome(adminUserService.setEnabled(id, false), id);
    }

    private static ResponseEntity<Void> mapSetEnabledOutcome(
            AdminUserService.SetEnabledOutcome outcome, UUID id) {
        if (outcome == AdminUserService.SetEnabledOutcome.NOT_FOUND) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User " + id + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/force-verify")
    public ResponseEntity<Void> forceVerify(@PathVariable UUID id) {
        if (!adminUserService.forceVerify(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User " + id + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{id}")
    public AdminUserDeleteResult delete(@PathVariable UUID id) {
        AdminUserDeleteResult result = adminUserService.delete(id);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User " + id + " not found");
        }
        return result;
    }
}
