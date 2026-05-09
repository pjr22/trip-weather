package com.pjr22.tripweather.routing;

import com.pjr22.tripweather.repository.RoutingCoverageRepository;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.StringReader;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * Seeds {@code routing_coverage} from Geofabrik {@code .poly} files, keeping
 * the dispatch wrapper's view of "what's covered" mechanically coupled to the
 * polygons Geofabrik clipped the OSM extracts to.
 *
 * <p>Two triggers, one work method:
 * <ol>
 *   <li>{@link ApplicationReadyEvent} on startup — for each region listed in
 *       {@code trip.routing.local-regions} that has no row, fetch + parse +
 *       insert.</li>
 *   <li>{@link #refresh(String)} called from the admin endpoint after a pbf
 *       swap — re-fetches and replaces the row for one region (the polygon
 *       can drift between Geofabrik refreshes as OSM boundaries evolve).</li>
 * </ol>
 *
 * <p>Per-region failures (network, parse) are logged at WARN and do not fail
 * startup. The dispatch wrapper handles a missing row by treating the region
 * as uncovered and falling back to public ORS — i.e. the safe default.
 */
@Component
@ConditionalOnProperty(name = "trip.local.ors.enabled", havingValue = "true")
@Slf4j
public class GeofabrikCoverageLoader {

    private final RestClient geofabrikRestClient;
    private final RoutingCoverageRepository repository;
    private final Clock clock;
    private final List<String> regions;

    public GeofabrikCoverageLoader(
            RestClient geofabrikRestClient,
            RoutingCoverageRepository repository,
            Clock clock,
            @Value("${trip.routing.local-regions:colorado}") String regionsCsv) {
        this.geofabrikRestClient = geofabrikRestClient;
        this.repository = repository;
        this.clock = clock;
        this.regions = parseRegions(regionsCsv);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedMissingRegions() {
        for (String region : regions) {
            if (repository.existsById(region)) {
                log.debug("Routing coverage already seeded for region '{}'", region);
                continue;
            }
            try {
                fetchAndStore(region);
            } catch (Exception e) {
                log.warn("Failed to seed routing coverage for region '{}': {}",
                        region, e.getMessage());
            }
        }
    }

    /**
     * Re-fetches and replaces the polygon for one region. Called by the
     * coverage-refresh admin endpoint after a successful pbf swap. Throws on
     * failure so the caller can return a non-2xx to the operator.
     */
    public void refresh(String region) {
        if (!regions.contains(region)) {
            throw new IllegalArgumentException("Region '" + region
                    + "' is not in trip.routing.local-regions");
        }
        fetchAndStore(region);
    }

    private void fetchAndStore(String region) {
        String body = geofabrikRestClient.get()
                .uri("/{region}.poly", region)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Empty .poly response for region '"
                    + region + "'");
        }
        MultiPolygon polygon;
        try {
            polygon = PolyParser.parse(new StringReader(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse .poly for region '"
                    + region + "'", e);
        }
        String wkt = new WKTWriter().write(polygon);
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
        repository.upsert(region, wkt, now);
        log.info("Seeded routing coverage for region '{}' ({} vertices)",
                region, countVertices(polygon));
    }

    private static long countVertices(MultiPolygon mp) {
        return mp.getNumPoints();
    }

    private static List<String> parseRegions(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
