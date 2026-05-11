package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.PbfFileCreateRequest;
import com.pjr22.tripweather.dto.PbfFileDto;
import com.pjr22.tripweather.dto.PbfFileUpdateRequest;
import com.pjr22.tripweather.service.PbfFileService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Admin Pbf-orchestration endpoints. Phase 2b of ADMIN_CONSOLE.md.
 *
 * <p>Authorization (ROLE_ADMIN session) is enforced by the admin
 * {@link org.springframework.security.web.SecurityFilterChain} declared in
 * {@link com.pjr22.tripweather.config.SecurityConfig}; the controller
 * doesn't check it directly.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET    /api/admin/pbfs} — list all rows, freshness-annotated.</li>
 *   <li>{@code GET    /api/admin/pbfs/{name}} — one row.</li>
 *   <li>{@code POST   /api/admin/pbfs} — create a new row.</li>
 *   <li>{@code PATCH  /api/admin/pbfs/{name}} — partial update of mutable fields.</li>
 *   <li>{@code DELETE /api/admin/pbfs/{name}} — drop the row; coverage rows
 *       linked to it have their {@code pbf_name} set to NULL via the FK.</li>
 *   <li>{@code POST   /api/admin/pbfs/{name}/schedule-now} — set
 *       {@code next_update_at = now()} so the next cron tick processes it.</li>
 *   <li>{@code POST   /api/admin/pbfs/{name}/check-md5} — fetch the .md5
 *       from Geofabrik right now and update {@code last_check_at}
 *       + {@code last_remote_md5} + {@code last_remote_modified}.
 *       Does NOT touch {@code next_check_at}.</li>
 *   <li>{@code POST   /api/admin/pbfs/{name}/retry-apply} — clear a stuck
 *       {@code last_apply_started_at} marker and set {@code next_update_at = now()}.
 *       For recovering from a crashed apply without waiting 4 h.</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/admin/pbfs", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class AdminPbfController {

    private final PbfFileService service;

    public AdminPbfController(PbfFileService service) {
        this.service = service;
    }

    @GetMapping
    public List<PbfFileDto> list() {
        return service.listAll();
    }

    @GetMapping("/{name}")
    public PbfFileDto get(@PathVariable("name") String name) {
        return service.findOne(name).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Pbf '" + name + "' not found"));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public PbfFileDto create(@Valid @RequestBody PbfFileCreateRequest request) {
        try {
            return service.create(request);
        } catch (PbfFileService.PbfFileAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @PatchMapping(value = "/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PbfFileDto update(@PathVariable("name") String name,
                             @Valid @RequestBody PbfFileUpdateRequest request) {
        try {
            return service.update(name, request);
        } catch (PbfFileService.PbfFileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable("name") String name) {
        try {
            service.delete(name);
        } catch (PbfFileService.PbfFileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{name}/schedule-now")
    public PbfFileDto scheduleNow(@PathVariable("name") String name) {
        try {
            return service.scheduleNow(name);
        } catch (PbfFileService.PbfFileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PostMapping("/{name}/check-md5")
    public PbfFileDto checkMd5(@PathVariable("name") String name) {
        try {
            return service.checkUpstreamNow(name);
        } catch (PbfFileService.PbfFileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (PbfFileService.PbfUpstreamCheckException e) {
            // Geofabrik fetch failed (network, 5xx, malformed body).
            // 502 Bad Gateway is the right shape — the upstream is the
            // problem, not the admin's input.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    @PostMapping("/{name}/retry-apply")
    public PbfFileDto retryApply(@PathVariable("name") String name) {
        try {
            return service.retryStuckApply(name);
        } catch (PbfFileService.PbfFileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }
}
