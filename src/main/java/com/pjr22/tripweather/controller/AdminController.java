package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.routing.GeofabrikCoverageLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * <p>Auth is a shared-secret header ({@code X-Admin-Token}) set via
 * {@code TRIP_ADMIN_REFRESH_TOKEN}. Stopgap until USER_ACCOUNTS_PLAN.md
 * defines an admin role. Production reachability is also gated by haproxy
 * (which does not route {@code /api/admin/**} from the public frontend);
 * this header check is defence in depth, not the primary control.
 *
 * <p>If the secret is unset, the endpoint refuses every call — fail closed.
 */
@RestController
@RequestMapping("/api/admin")
@Slf4j
public class AdminController {

    private final ObjectProvider<GeofabrikCoverageLoader> coverageLoaderProvider;
    private final String expectedToken;

    public AdminController(ObjectProvider<GeofabrikCoverageLoader> coverageLoaderProvider,
                           @Value("${trip.admin.refresh-token:}") String expectedToken) {
        this.coverageLoaderProvider = coverageLoaderProvider;
        this.expectedToken = expectedToken;
    }

    @PostMapping("/refresh-coverage/{region}")
    public ResponseEntity<Map<String, String>> refreshCoverage(
            @PathVariable String region,
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {

        if (expectedToken == null || expectedToken.isBlank()) {
            log.warn("Coverage refresh denied: TRIP_ADMIN_REFRESH_TOKEN is unset");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "admin token not configured"));
        }
        if (!expectedToken.equals(token)) {
            log.warn("Coverage refresh denied for region '{}': bad/missing X-Admin-Token", region);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "forbidden"));
        }

        GeofabrikCoverageLoader loader = coverageLoaderProvider.getIfAvailable();
        if (loader == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "local ORS not enabled (trip.local.ors.enabled=false)"));
        }

        try {
            loader.refresh(region);
            return ResponseEntity.ok(Map.of("status", "refreshed", "region", region));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Coverage refresh failed for region '{}'", region, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
