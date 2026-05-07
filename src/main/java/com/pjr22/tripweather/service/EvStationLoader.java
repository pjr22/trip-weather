package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.repository.EvStationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Weekly loader that mirrors the NREL Alternative Fuels Data Center electric
 * station dataset into the {@code ev_stations} table. Replaces per-route
 * upstream calls in {@link EVChargingStationService}.
 *
 * <p>Three triggers, one work method:
 * <ol>
 *   <li>Cron (default Monday 04:00 server-local) — the steady-state refresh.</li>
 *   <li>{@link ApplicationReadyEvent} when the table is empty — bootstraps a
 *       fresh checkout / first deployment without waiting for Monday.</li>
 *   <li>1-hour retry scheduled by the loader itself when a run fails — keeps
 *       trying until one succeeds, so a transient NREL outage doesn't push
 *       the next refresh out by a week.</li>
 * </ol>
 *
 * <p>A {@link ReentrantLock#tryLock() tryLock} guards against the
 * cron-and-retry overlap (a 1h retry that lands on Monday 04:00 would
 * otherwise double-run); the second runner exits without work.
 *
 * <p>Mirror-vs-NREL-parity invariant: only {@code fuel_type=ELEC} stations
 * are mirrored. The user-facing query side rejects any other {@code fuel_type}
 * by returning empty — same behavior the UI sees today, since it only ever
 * sends {@code ELEC}. Stations that disappear from a fetch are flagged
 * {@code active=false} rather than deleted, so a station that comes back the
 * following week is reactivated in place.
 */
@Component
@Slf4j
public class EvStationLoader {

    private static final String BULK_PATH =
            "/api/alt-fuel-stations/v1.json?fuel_type=ELEC&limit=all&api_key=%s";

    private static final int BATCH_SIZE = 500;

    private static final Duration RETRY_DELAY = Duration.ofHours(1);

    private static final String UPSERT_SQL = """
            INSERT INTO ev_stations (
                id, location, fuel_type_code, status_code, access_code, ev_network,
                ev_connector_types, ev_dc_fast_num, ev_level1_evse_num, ev_level2_evse_num,
                properties, last_seen_at, active, fetched_at
            ) VALUES (
                ?, ST_GeographyFromText(?), ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, TRUE, ?
            )
            ON CONFLICT (id) DO UPDATE SET
                location           = EXCLUDED.location,
                fuel_type_code     = EXCLUDED.fuel_type_code,
                status_code        = EXCLUDED.status_code,
                access_code        = EXCLUDED.access_code,
                ev_network         = EXCLUDED.ev_network,
                ev_connector_types = EXCLUDED.ev_connector_types,
                ev_dc_fast_num     = EXCLUDED.ev_dc_fast_num,
                ev_level1_evse_num = EXCLUDED.ev_level1_evse_num,
                ev_level2_evse_num = EXCLUDED.ev_level2_evse_num,
                properties         = EXCLUDED.properties,
                last_seen_at       = EXCLUDED.last_seen_at,
                active             = TRUE,
                fetched_at         = EXCLUDED.fetched_at
            """;

    private static final String DEACTIVATE_MISSING_SQL = """
            UPDATE ev_stations
               SET active = FALSE
             WHERE active = TRUE
               AND last_seen_at < ?
            """;

    private final RestClient restClient;
    private final EvStationRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final String apiKey;
    private final boolean loaderEnabled;
    private final boolean bootstrapOnEmpty;

    private final ReentrantLock runLock = new ReentrantLock();

    public EvStationLoader(RestClient nrelRestClient,
                           EvStationRepository repository,
                           JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper,
                           TaskScheduler taskScheduler,
                           Clock clock,
                           @Value("${nrel.api.key}") String apiKey,
                           @Value("${trip.ev.loader-enabled:true}") boolean loaderEnabled,
                           @Value("${trip.ev.loader-bootstrap-on-empty:true}") boolean bootstrapOnEmpty) {
        this.restClient = nrelRestClient;
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
        this.apiKey = apiKey;
        this.loaderEnabled = loaderEnabled;
        this.bootstrapOnEmpty = bootstrapOnEmpty;
    }

    @Scheduled(cron = "${trip.ev.loader-cron:0 0 4 * * MON}")
    public void scheduledLoad() {
        if (!loaderEnabled) {
            log.debug("EV station loader disabled (trip.ev.loader-enabled=false); skipping.");
            return;
        }
        runWithRetryOnFailure();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapOnEmpty() {
        if (!loaderEnabled || !bootstrapOnEmpty) {
            return;
        }
        long count;
        try {
            count = repository.count();
        } catch (Exception e) {
            // Table missing (Phase 3 migration not run yet) or DB unreachable —
            // log a one-liner and let the operator notice; don't crash startup.
            log.warn("EV station bootstrap skipped: cannot count ev_stations ({})", e.getMessage());
            return;
        }
        if (count > 0) {
            log.info("EV station mirror has {} row(s); skipping bootstrap.", count);
            return;
        }
        log.info("EV station mirror is empty; scheduling bootstrap load.");
        // Schedule rather than run inline: ApplicationReadyEvent listeners
        // shouldn't block app readiness. 5-second delay is just to let the
        // event-publishing thread return cleanly.
        taskScheduler.schedule(this::runWithRetryOnFailure,
                clock.instant().plusSeconds(5));
    }

    /** Single attempt; on failure, schedule a retry RETRY_DELAY out and log
     *  WARN. Public for tests. */
    void runWithRetryOnFailure() {
        if (!runLock.tryLock()) {
            log.info("EV station load already in progress; skipping this trigger.");
            return;
        }
        try {
            long start = System.currentTimeMillis();
            int loaded = load();
            log.info("EV station load complete: {} station(s) upserted in {}ms",
                    loaded, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("EV station load failed; scheduling retry in {}", RETRY_DELAY, e);
            taskScheduler.schedule(this::runWithRetryOnFailure,
                    clock.instant().plus(RETRY_DELAY));
        } finally {
            runLock.unlock();
        }
    }

    /** Returns the number of stations loaded. Visible for tests. */
    int load() throws Exception {
        LocalDateTime fetchStartedAt = LocalDateTime.now(clock);

        String body = restClient.get()
                .uri(String.format(Locale.ROOT, BULK_PATH, apiKey))
                .retrieve()
                .body(String.class);
        if (body == null || body.isEmpty()) {
            throw new IllegalStateException("NREL bulk feed returned empty body");
        }

        JsonNode root = objectMapper.readTree(body);
        JsonNode features = root.get("features");
        if (features == null || !features.isArray()) {
            throw new IllegalStateException("NREL bulk feed missing 'features' array");
        }

        List<StationRow> batch = new ArrayList<>(BATCH_SIZE);
        int loaded = 0;
        for (JsonNode feature : features) {
            StationRow row = parseFeature(feature, fetchStartedAt);
            if (row == null) {
                continue;
            }
            batch.add(row);
            if (batch.size() >= BATCH_SIZE) {
                upsertBatch(batch);
                loaded += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            upsertBatch(batch);
            loaded += batch.size();
        }

        // Mark stations missing from this fetch as inactive. Anything still
        // active with a last_seen_at older than this run's start can't have
        // been seen during this run.
        int deactivated = jdbcTemplate.update(DEACTIVATE_MISSING_SQL,
                Timestamp.valueOf(fetchStartedAt));
        if (deactivated > 0) {
            log.info("EV station load: deactivated {} station(s) absent from this fetch", deactivated);
        }
        return loaded;
    }

    private StationRow parseFeature(JsonNode feature, LocalDateTime fetchedAt) {
        JsonNode geometry = feature.get("geometry");
        JsonNode properties = feature.get("properties");
        if (geometry == null || properties == null) {
            return null;
        }
        JsonNode coords = geometry.get("coordinates");
        if (coords == null || !coords.isArray() || coords.size() < 2) {
            return null;
        }
        if (properties.get("id") == null || !properties.get("id").isIntegralNumber()) {
            return null;
        }

        long id = properties.get("id").asLong();
        double lon = coords.get(0).asDouble();
        double lat = coords.get(1).asDouble();

        List<String> connectorTypes = new ArrayList<>();
        JsonNode connectorTypesNode = properties.get("ev_connector_types");
        if (connectorTypesNode != null && connectorTypesNode.isArray()) {
            for (JsonNode c : connectorTypesNode) {
                if (c != null && c.isTextual()) {
                    connectorTypes.add(c.asText());
                }
            }
        }

        String propertiesJson;
        try {
            propertiesJson = objectMapper.writeValueAsString(properties);
        } catch (Exception e) {
            log.warn("Could not serialize properties for station id {}; skipping", id, e);
            return null;
        }

        return new StationRow(
                id,
                lon, lat,
                textOrNull(properties, "fuel_type_code"),
                textOrNull(properties, "status_code"),
                textOrNull(properties, "access_code"),
                textOrNull(properties, "ev_network"),
                connectorTypes,
                intOrNull(properties, "ev_dc_fast_num"),
                intOrNull(properties, "ev_level1_evse_num"),
                intOrNull(properties, "ev_level2_evse_num"),
                propertiesJson,
                fetchedAt);
    }

    private void upsertBatch(List<StationRow> batch) {
        jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                StationRow r = batch.get(i);
                Timestamp ts = Timestamp.valueOf(r.fetchedAt());
                Array connectors = ps.getConnection().createArrayOf(
                        "text", r.connectorTypes().toArray(new String[0]));
                int idx = 1;
                ps.setLong(idx++, r.id());
                ps.setString(idx++, String.format(Locale.ROOT, "POINT(%f %f)", r.lon(), r.lat()));
                ps.setString(idx++, r.fuelTypeCode());
                ps.setString(idx++, r.statusCode());
                ps.setString(idx++, r.accessCode());
                ps.setString(idx++, r.evNetwork());
                ps.setArray(idx++, connectors);
                setNullableInt(ps, idx++, r.evDcFastNum());
                setNullableInt(ps, idx++, r.evLevel1EvseNum());
                setNullableInt(ps, idx++, r.evLevel2EvseNum());
                ps.setString(idx++, r.propertiesJson());
                ps.setTimestamp(idx++, ts);   // last_seen_at
                ps.setTimestamp(idx++, ts);   // fetched_at
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, value);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() && v.isTextual() ? v.asText() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() && v.isIntegralNumber() ? v.asInt() : null;
    }

    private record StationRow(
            long id,
            double lon, double lat,
            String fuelTypeCode,
            String statusCode,
            String accessCode,
            String evNetwork,
            List<String> connectorTypes,
            Integer evDcFastNum,
            Integer evLevel1EvseNum,
            Integer evLevel2EvseNum,
            String propertiesJson,
            LocalDateTime fetchedAt
    ) {}
}
