package com.pjr22.tripweather.routing;

import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.repository.RoutingCoverageRepository;
import com.pjr22.tripweather.service.LoaderRunRecorder;
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

    /** Loader-name prefix; one {@code loader_runs} row per region uses
     *  {@code "ors-coverage:" + region} as the loader name. */
    public static final String LOADER_NAME_PREFIX = "ors-coverage:";

    private final RestClient geofabrikRestClient;
    private final RoutingCoverageRepository repository;
    private final Clock clock;
    private final LoaderRunRecorder recorder;
    private final List<String> regions;

    public GeofabrikCoverageLoader(
            RestClient geofabrikRestClient,
            RoutingCoverageRepository repository,
            Clock clock,
            LoaderRunRecorder recorder,
            @Value("${trip.routing.local-regions:colorado}") String regionsCsv) {
        this.geofabrikRestClient = geofabrikRestClient;
        this.repository = repository;
        this.clock = clock;
        this.recorder = recorder;
        this.regions = parseRegions(regionsCsv);
    }

    /** Configured regions, in declaration order. Exposed for the admin
     *  loaders endpoint so it can list all known coverage loaders even
     *  when none have run yet. */
    public List<String> getRegions() {
        return regions;
    }

    /** Loader name for the {@code loader_runs} row that records work for
     *  one region. Public so callers (admin trigger, legacy refresh
     *  endpoint) can construct the same name without duplicating the
     *  {@code "ors-coverage:"} prefix. */
    public static String loaderName(String region) {
        return LOADER_NAME_PREFIX + region;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedMissingRegions() {
        for (String region : regions) {
            if (repository.existsById(region)) {
                log.debug("Routing coverage already seeded for region '{}'", region);
                continue;
            }
            // Each missing-region seed is its own BOOTSTRAP run. Failures
            // are logged at WARN inside fetchAndStoreRecorded — this loop
            // intentionally does not propagate them so a single broken
            // .poly file doesn't keep us from seeding the rest.
            try {
                fetchAndStoreRecorded(region, TriggerType.BOOTSTRAP);
            } catch (Exception e) {
                log.warn("Failed to seed routing coverage for region '{}': {}",
                        region, e.getMessage());
            }
        }
    }

    /**
     * Re-fetches and replaces the polygon for one region, recording a
     * {@code loader_runs} row under {@code ors-coverage:{region}}.
     *
     * <p>Called from two places:
     * <ul>
     *   <li>The legacy {@code POST /api/admin/refresh-coverage/{region}}
     *       endpoint (production cron's {@code docker/refreshOrsGraph.sh})
     *       — passes {@link TriggerType#CRON}.</li>
     *   <li>The Phase 2 admin console manual trigger
     *       ({@code POST /api/admin/loaders/ors-coverage/{region}/trigger})
     *       — passes {@link TriggerType#MANUAL}.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the region is not in
     *         {@code trip.routing.local-regions}
     * @throws LoaderRunRecorder.RunInProgressException if another run for
     *         the same region is already in flight (caller maps to 409)
     */
    public void refresh(String region, TriggerType trigger) {
        if (!regions.contains(region)) {
            throw new IllegalArgumentException("Region '" + region
                    + "' is not in trip.routing.local-regions");
        }
        fetchAndStoreRecorded(region, trigger);
    }

    private void fetchAndStoreRecorded(String region, TriggerType trigger) {
        LoaderRun run = recorder.start(loaderName(region), trigger);
        try {
            fetchAndStore(region);
            // Coverage refresh affects exactly one row (UPSERT into
            // routing_coverage); reflect that as the rows-affected count.
            recorder.success(run, 1L);
        } catch (Exception e) {
            recorder.fail(run, e);
            throw e;
        }
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
