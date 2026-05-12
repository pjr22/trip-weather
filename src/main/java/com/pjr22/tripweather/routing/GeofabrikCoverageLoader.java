package com.pjr22.tripweather.routing;

import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.model.PbfFile;
import com.pjr22.tripweather.repository.PbfFileRepository;
import com.pjr22.tripweather.repository.RoutingCoverageRepository;
import com.pjr22.tripweather.service.LoaderRunRecorder;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.StringReader;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Fetches Geofabrik {@code .poly} files for managed pbfs and writes them
 * into {@code routing_coverage}, keeping the dispatch wrapper's view of
 * "what's covered" mechanically coupled to the polygons Geofabrik clipped
 * the OSM extracts to.
 *
 * <p>Phase 2c: one routing_coverage row per pbf_files row, name == pbf_name,
 * FK cascade. The loader resolves the .poly URL from the pbf row's
 * {@code geofabrik_url} (swap {@code -latest.osm.pbf} → {@code .poly}).
 *
 * <p>Triggers: the legacy {@code POST /api/admin/refresh-coverage/{region}}
 * endpoint (called by {@code docker/refreshOrsGraph.sh} after a pbf apply)
 * and the admin console's per-loader trigger
 * ({@code POST /api/admin/loaders/ors-coverage:{pbfName}/trigger}). Both
 * land in {@link #refresh(String, TriggerType)}.
 *
 * <p>The startup {@code ApplicationReadyEvent} bootstrap that Phase 2b had
 * (seed-missing-regions from {@code trip.routing.local-regions}) is removed
 * — Phase 2c routes coverage entirely through admin actions and the cron's
 * post-apply step. Fresh installs have empty routing_coverage; every
 * routing call falls back to public ORS until the admin adds a pbf.
 */
@Component
@ConditionalOnProperty(name = "trip.local.ors.enabled", havingValue = "true")
@Slf4j
public class GeofabrikCoverageLoader {

    /** Loader-name prefix; one {@code loader_runs} row per pbf uses
     *  {@code "ors-coverage:" + pbfName} as the loader name. */
    public static final String LOADER_NAME_PREFIX = "ors-coverage:";

    private final RestClient geofabrikRestClient;
    private final PbfFileRepository pbfFileRepository;
    private final RoutingCoverageRepository repository;
    private final Clock clock;
    private final LoaderRunRecorder recorder;

    public GeofabrikCoverageLoader(
            RestClient geofabrikRestClient,
            PbfFileRepository pbfFileRepository,
            RoutingCoverageRepository repository,
            Clock clock,
            LoaderRunRecorder recorder) {
        this.geofabrikRestClient = geofabrikRestClient;
        this.pbfFileRepository = pbfFileRepository;
        this.repository = repository;
        this.clock = clock;
        this.recorder = recorder;
    }

    /** Loader name for the {@code loader_runs} row that records work for
     *  one pbf. Public so callers (admin trigger, legacy refresh endpoint)
     *  can construct the same name without duplicating the prefix. */
    public static String loaderName(String pbfName) {
        return LOADER_NAME_PREFIX + pbfName;
    }

    /**
     * Re-fetches the polygon for one pbf and replaces its
     * {@code routing_coverage} row's {@code geom} + {@code fetched_at}.
     * The {@code enabled} column is left untouched — that's the admin's
     * manual toggle.
     *
     * @throws IllegalArgumentException if no pbf row exists with the given
     *         name (caller maps to HTTP 404)
     * @throws LoaderRunRecorder.RunInProgressException if another run for
     *         the same pbf is already in flight (caller maps to HTTP 409)
     */
    public void refresh(String pbfName, TriggerType trigger) {
        PbfFile pbf = pbfFileRepository.findById(pbfName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No pbf_files row named '" + pbfName + "'"));
        fetchAndStoreRecorded(pbf, trigger);
    }

    private void fetchAndStoreRecorded(PbfFile pbf, TriggerType trigger) {
        LoaderRun run = recorder.start(loaderName(pbf.getPbfName()), trigger);
        try {
            fetchAndStore(pbf);
            // Coverage refresh affects exactly one row (UPSERT into
            // routing_coverage); reflect that as the rows-affected count.
            recorder.success(run, 1L);
        } catch (Exception e) {
            recorder.fail(run, e);
            throw e;
        }
    }

    private void fetchAndStore(PbfFile pbf) {
        URI polyUrl = derivePolyUrl(pbf.getGeofabrikUrl());
        String body = geofabrikRestClient.get()
                .uri(polyUrl)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Empty .poly response from " + polyUrl);
        }
        MultiPolygon polygon;
        try {
            polygon = PolyParser.parse(new StringReader(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse .poly from " + polyUrl, e);
        }
        String wkt = new WKTWriter().write(polygon);
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
        repository.upsertPolygon(pbf.getPbfName(), wkt, now);
        log.info("Refreshed routing coverage for pbf '{}' ({} vertices)",
                pbf.getPbfName(), countVertices(polygon));
    }

    /**
     * Swaps the conventional Geofabrik suffix {@code -latest.osm.pbf} for
     * {@code .poly}. Works for both sub-region extracts (us-west,
     * colorado, etc.) and individual states; the path layout matches
     * one-for-one. Non-standard URLs would need an explicit poly_url
     * column on pbf_files — deferred until a deployment hits it.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code https://download.geofabrik.de/north-america/us-west-latest.osm.pbf}
     *       → {@code https://download.geofabrik.de/north-america/us-west.poly}</li>
     *   <li>{@code .../us/colorado-latest.osm.pbf} → {@code .../us/colorado.poly}</li>
     * </ul>
     */
    static URI derivePolyUrl(String pbfUrl) {
        if (pbfUrl == null || !pbfUrl.endsWith("-latest.osm.pbf")) {
            throw new IllegalStateException(
                    "geofabrik_url does not match the '-latest.osm.pbf' convention; "
                  + "cannot derive .poly URL: " + pbfUrl);
        }
        return URI.create(pbfUrl.substring(0, pbfUrl.length() - "-latest.osm.pbf".length())
                + ".poly");
    }

    private static long countVertices(MultiPolygon mp) {
        return mp.getNumPoints();
    }
}
