package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.LoaderRunDto;
import com.pjr22.tripweather.dto.LoaderSummaryDto;
import com.pjr22.tripweather.service.AdminLoaderService;
import com.pjr22.tripweather.service.LoaderRunRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Admin loader / cleanup observability + manual triggers. Phase 2 of
 * ADMIN_CONSOLE.md.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/admin/loaders} — one summary per known loader
 *       (last-run status + timestamps).</li>
 *   <li>{@code GET    /api/admin/loaders/{name}/runs?limit=N} — recent
 *       runs for one loader, newest first.</li>
 *   <li>{@code POST   /api/admin/loaders/{name}/trigger} — kick off a
 *       manual run; 202 if accepted, 409 if already in flight, 404 if
 *       the loader name doesn't match a known loader, 503 if it's an
 *       ors-coverage loader and local ORS is disabled.</li>
 *   <li>{@code POST   /api/admin/loaders/ors-coverage/{region}/trigger}
 *       — convenience URL for triggering a single coverage region;
 *       same semantics as the generic trigger.</li>
 *   <li>{@code POST   /api/admin/loaders/ors-coverage/refresh-all} —
 *       fans the coverage refresh over every configured region
 *       sequentially on a background task; 202 with the list of
 *       enqueued regions.</li>
 * </ul>
 *
 * <p>Authorization is enforced by the admin {@link
 * org.springframework.security.web.SecurityFilterChain SecurityFilterChain}
 * (see {@link com.pjr22.tripweather.config.SecurityConfig}); every endpoint
 * here requires {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping(value = "/api/admin/loaders", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class AdminLoaderController {

    private final AdminLoaderService service;

    public AdminLoaderController(AdminLoaderService service) {
        this.service = service;
    }

    @GetMapping
    public List<LoaderSummaryDto> list() {
        return service.listLoaders();
    }

    @GetMapping("/{name}/runs")
    public List<LoaderRunDto> runs(@PathVariable("name") String name,
                                   @RequestParam(name = "limit", required = false,
                                           defaultValue = "20") int limit) {
        return service.history(name, limit);
    }

    @PostMapping("/{name}/trigger")
    public ResponseEntity<Void> trigger(@PathVariable("name") String name) {
        try {
            service.triggerByName(name);
            return ResponseEntity.accepted().build();
        } catch (LoaderRunRecorder.RunInProgressException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /**
     * Per-region trigger. Equivalent to
     * {@code POST /api/admin/loaders/ors-coverage:{region}/trigger} but
     * the path-segment shape is more URL-friendly (no embedded colon).
     */
    @PostMapping("/ors-coverage/{region}/trigger")
    public ResponseEntity<Void> triggerCoverageRegion(@PathVariable("region") String region) {
        // Delegate to the same triggerByName path so behaviour, logging,
        // and error mapping stay in one place. The colon-prefixed loader
        // name is what gets recorded.
        return trigger("ors-coverage:" + region);
    }

    /**
     * Refresh every configured coverage region sequentially. 202 with
     * {@code {regions: [...]}} listing the enqueued regions; the actual
     * runs unfold over the background task. 503 when local ORS is off.
     */
    @PostMapping("/ors-coverage/refresh-all")
    public ResponseEntity<Map<String, Object>> refreshAllCoverage() {
        try {
            List<String> regions = service.refreshAllCoverageRegions();
            return ResponseEntity.accepted()
                    .body(Map.of("regions", regions, "count", regions.size()));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }
}
