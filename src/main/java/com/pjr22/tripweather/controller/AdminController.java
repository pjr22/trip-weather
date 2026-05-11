package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.routing.GeofabrikCoverageLoader;
import com.pjr22.tripweather.service.LoaderRunRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal-only operator endpoints. The single endpoint today is
 * {@code POST /api/admin/refresh-coverage/{region}}, called by
 * {@code docker/refreshOrsGraph.sh} after a successful pbf swap so the
 * routing-coverage polygon stays in sync with what the engine actually
 * serves.
 *
 * <p>Authorization is enforced by the admin {@link
 * org.springframework.security.web.SecurityFilterChain SecurityFilterChain}:
 * this endpoint requires either {@code ROLE_ADMIN} (a logged-in admin session)
 * or {@code ROLE_ADMIN_TOKEN} (granted by
 * {@link com.pjr22.tripweather.security.XAdminTokenAuthenticationFilter} when
 * the request carries a matching {@code X-Admin-Token} header — the production
 * cron's auth path). All other admin endpoints require {@code ROLE_ADMIN} only.
 */
@RestController
@RequestMapping("/api/admin")
@Slf4j
public class AdminController {

    private final ObjectProvider<GeofabrikCoverageLoader> coverageLoaderProvider;

    public AdminController(ObjectProvider<GeofabrikCoverageLoader> coverageLoaderProvider) {
        this.coverageLoaderProvider = coverageLoaderProvider;
    }

    @PostMapping("/refresh-coverage/{region}")
    public ResponseEntity<Map<String, String>> refreshCoverage(@PathVariable String region) {
        GeofabrikCoverageLoader loader = coverageLoaderProvider.getIfAvailable();
        if (loader == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "local ORS not enabled (trip.local.ors.enabled=false)"));
        }

        // Phase 2 of ADMIN_CONSOLE.md: every refresh records a loader_runs
        // row. Production cron (docker/refreshOrsGraph.sh) calls this
        // endpoint, so its refreshes show up in the admin history alongside
        // manual ones with trigger=CRON / MANUAL respectively.
        try {
            loader.refresh(region, TriggerType.CRON);
            return ResponseEntity.ok(Map.of("status", "refreshed", "region", region));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (LoaderRunRecorder.RunInProgressException e) {
            // Another refresh (manual or cron) is in flight for this
            // region. Tell the cron to back off; on the next minute's
            // crontab tick the in-flight run will have finished.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Coverage refresh failed for region '{}'", region, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
