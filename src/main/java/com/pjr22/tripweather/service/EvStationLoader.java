package com.pjr22.tripweather.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.repository.EvStationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Weekly loader that mirrors the NREL/NLR Alternative Fuels Data Center
 * electric station dataset into the {@code ev_stations} table. Replaces
 * per-route upstream calls in {@link EVChargingStationService}.
 *
 * <p>Three triggers, one work method:
 * <ol>
 *   <li>Cron (default Monday 04:00 server-local) — the steady-state refresh.</li>
 *   <li>{@link ApplicationReadyEvent} when the table is empty — bootstraps a
 *       fresh checkout / first deployment without waiting for Monday.</li>
 *   <li>1-hour retry scheduled by the loader itself when a run fails — keeps
 *       trying until one succeeds, so a transient outage doesn't push the
 *       next refresh out by a week. 429 responses use the upstream
 *       {@code Retry-After} header if present.</li>
 * </ol>
 *
 * <p>A {@link ReentrantLock#tryLock() tryLock} guards against the
 * cron-and-retry overlap (a 1h retry that lands on Monday 04:00 would
 * otherwise double-run); the second runner exits without work.
 *
 * <p>Mirror-vs-upstream-parity invariant: only {@code fuel_type=ELEC}
 * stations are mirrored. The user-facing query side rejects any other
 * {@code fuel_type} by returning empty — same behavior the UI sees today,
 * since it only ever sends {@code ELEC}. Stations that disappear from a
 * fetch are flagged {@code active=false} rather than deleted, so a station
 * that comes back the following week is reactivated in place.
 *
 * <p>Transport details: the endpoint has no {@code offset} parameter and
 * its single-response output is silently capped around 50,000 records (a
 * full {@code limit=all} call closes the connection mid-stream past that
 * mark). To stay below the cap and still cover the full dataset we issue
 * 13 filtered calls — California alone, the other 49 US states + DC in
 * groups of 5, US territories together, and Canada — each with its own
 * {@code limit=all} response of at most ~21K records. Two pieces make
 * each call practical:
 * {@link com.pjr22.tripweather.config.HttpClientConfig#nrelRestClient}
 * forces HTTP/1.1 (the JDK's default HTTP/2 client RST_STREAMs long
 * responses), and {@link #streamParseFeatures} drives a Jackson
 * incremental parser so the body never lands fully in memory.
 */
@Component
@Slf4j
public class EvStationLoader {

    // The endpoint has no `offset` parameter and silently caps a single
    // limit=all response at ~50,000 records (server side closes the
    // connection mid-stream past that). We split the load into ~13 filtered
    // calls instead — each well below the cap, all using limit=all. The
    // .geojson form (vs .json) wraps each station as a Feature with
    // geometry + properties, which is what parseFeature expects.
    //
    // Format string takes the filter expression (e.g. "state=CA",
    // "state=AL,AK,AZ,AR,CO", "country=CA") and the API key.
    private static final String BATCH_PATH_TEMPLATE =
            "/api/alt-fuel-stations/v1.geojson?fuel_type=ELEC&%s&limit=all&api_key=%s";

    /**
     * The 13 batches that together cover every NLR-tracked ELEC station the
     * app cares about. Order is deterministic so per-batch logs are
     * predictable. California is its own batch because it's the largest
     * single bucket (~21K stations); the other 49 US states + DC are
     * grouped 5 per call to keep the call count near 10; territories and
     * Canada (a separate country, distinct from California despite the
     * shared "CA" code) each get one call.
     *
     * <p>Each {@link Batch}'s {@code label} is for log lines and never
     * contains the API key; {@code filter} is the URL-ready filter clause.
     */
    private static final List<Batch> BATCHES = List.of(
            new Batch("California (state=CA)",          "state=CA"),
            new Batch("US states  1/10: AL,AK,AZ,AR,CO",  "state=AL,AK,AZ,AR,CO"),
            new Batch("US states  2/10: CT,DE,FL,GA,HI",  "state=CT,DE,FL,GA,HI"),
            new Batch("US states  3/10: ID,IL,IN,IA,KS",  "state=ID,IL,IN,IA,KS"),
            new Batch("US states  4/10: KY,LA,ME,MD,MA",  "state=KY,LA,ME,MD,MA"),
            new Batch("US states  5/10: MI,MN,MS,MO,MT",  "state=MI,MN,MS,MO,MT"),
            new Batch("US states  6/10: NE,NV,NH,NJ,NM",  "state=NE,NV,NH,NJ,NM"),
            new Batch("US states  7/10: NY,NC,ND,OH,OK",  "state=NY,NC,ND,OH,OK"),
            new Batch("US states  8/10: OR,PA,RI,SC,SD",  "state=OR,PA,RI,SC,SD"),
            new Batch("US states  9/10: TN,TX,UT,VT,VA",  "state=TN,TX,UT,VT,VA"),
            new Batch("US states 10/10: WA,WV,WI,WY,DC",  "state=WA,WV,WI,WY,DC"),
            new Batch("US territories: PR,VI,GU,AS,MP",   "state=PR,VI,GU,AS,MP"),
            new Batch("Canada (country=CA)",              "country=CA")
    );

    private record Batch(String label, String filter) {}

    private static final int BATCH_SIZE = 500;

    private static final int PROGRESS_LOG_INTERVAL = 5_000;

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
        // Schedule rather than run inline: ApplicationReadyEvent listeners
        // shouldn't block app readiness. 5-second delay is just to let the
        // event-publishing thread return cleanly.
        long delaySeconds = 5;
        log.info("EV station mirror is empty; scheduling bootstrap load to run in {} seconds.",
                delaySeconds);
        taskScheduler.schedule(this::runWithRetryOnFailure,
                clock.instant().plusSeconds(delaySeconds));
    }

    /** Single attempt; on failure, schedule a retry RETRY_DELAY out and log
     *  WARN. Public for tests. */
    void runWithRetryOnFailure() {
        if (!runLock.tryLock()) {
            log.info("EV station load already in progress; skipping this trigger.");
            return;
        }
        try {
            log.info("EV station load starting ({} filtered batches, streaming parse).",
                    BATCHES.size());
            long start = System.currentTimeMillis();
            int loaded = load();
            log.info("EV station load complete: {} station(s) upserted in {}ms",
                    loaded, System.currentTimeMillis() - start);
        } catch (HttpClientErrorException.TooManyRequests e) {
            // NREL has a per-API-key request budget (1000/hr on the free tier).
            // Honor a Retry-After header if present; otherwise wait the
            // default — usually long enough for the rolling window to clear.
            Duration delay = parseRetryAfter(e.getResponseHeaders()).orElse(RETRY_DELAY);
            Instant retryAt = clock.instant().plus(delay);
            log.warn("NREL rate limit exceeded (429 OVER_RATE_LIMIT); scheduling retry at {} (in {}). " +
                            "Upgrade your NREL API key if this recurs.",
                    retryAt, delay, e);
            taskScheduler.schedule(this::runWithRetryOnFailure, retryAt);
        } catch (Exception e) {
            Instant retryAt = clock.instant().plus(RETRY_DELAY);
            log.warn("EV station load failed; scheduling retry at {} (in {})",
                    retryAt, RETRY_DELAY, e);
            taskScheduler.schedule(this::runWithRetryOnFailure, retryAt);
        } finally {
            runLock.unlock();
        }
    }

    /** Parse RFC 7231 Retry-After (delta-seconds form). HTTP-date form is
     *  rare for rate-limit responses; we fall back to the default delay
     *  if the header is missing or in date form. */
    private static Optional<Duration> parseRetryAfter(HttpHeaders headers) {
        if (headers == null) return Optional.empty();
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
        } catch (NumberFormatException ignore) {
            return Optional.empty();
        }
    }

    /** Returns the number of stations loaded. Visible for tests. */
    int load() throws Exception {
        LocalDateTime fetchStartedAt = LocalDateTime.now(clock);
        int totalLoaded = 0;
        int totalBatches = BATCHES.size();

        for (int i = 0; i < totalBatches; i++) {
            Batch batch = BATCHES.get(i);
            int batchNum = i + 1;
            log.info("EV station load: fetching batch {}/{} — {}",
                    batchNum, totalBatches, batch.label());
            long batchStart = System.currentTimeMillis();
            int batchLoaded = fetchAndIngestBatch(batch, fetchStartedAt);
            totalLoaded += batchLoaded;
            log.info("EV station load: batch {}/{} done — {} station(s) in {}ms",
                    batchNum, totalBatches, batchLoaded,
                    System.currentTimeMillis() - batchStart);
        }

        // Mark stations missing from this fetch as inactive. Anything still
        // active with a last_seen_at older than this run's start can't have
        // been seen during this run. Only runs after every batch succeeded;
        // a partial run's batches stay {@code active=true} so a retry that
        // covers the missing batches doesn't accidentally deactivate them.
        int deactivated = jdbcTemplate.update(DEACTIVATE_MISSING_SQL,
                Timestamp.valueOf(fetchStartedAt));
        if (deactivated > 0) {
            log.info("EV station load: deactivated {} station(s) absent from this fetch", deactivated);
        }
        return totalLoaded;
    }

    /** Fetch one batch via .exchange() so the InputStream goes straight
     *  into the streaming parser. Non-2xx statuses re-thrown as
     *  HttpClientErrorException so runWithRetryOnFailure's 429 catch fires. */
    private int fetchAndIngestBatch(Batch batch, LocalDateTime fetchStartedAt) {
        String path = String.format(Locale.ROOT, BATCH_PATH_TEMPLATE,
                batch.filter(), apiKey);
        return restClient.get()
                .uri(path)
                .exchange((req, res) -> {
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        byte[] errorBody = res.getBody().readAllBytes();
                        throw HttpClientErrorException.create(
                                res.getStatusCode(), res.getStatusText(),
                                res.getHeaders(), errorBody, StandardCharsets.UTF_8);
                    }
                    try (InputStream in = res.getBody();
                         JsonParser parser = objectMapper.getFactory().createParser(in)) {
                        return streamParseFeatures(parser, fetchStartedAt);
                    }
                });
    }

    /** Walks the FeatureCollection's "features" array one element at a time,
     *  handing each to {@link #parseFeature} and batching upserts. Holds at
     *  most one Feature plus the current batch in memory. */
    private int streamParseFeatures(JsonParser parser, LocalDateTime fetchStartedAt) throws java.io.IOException {
        // Skip ahead to the "features" field. Top-level shape is
        // {type, metadata, features:[...]}; metadata can come before or
        // after features depending on server ordering, but features is the
        // only field we need.
        JsonToken token;
        boolean foundFeatures = false;
        while ((token = parser.nextToken()) != null) {
            if (token == JsonToken.FIELD_NAME && "features".equals(parser.currentName())) {
                JsonToken arrayStart = parser.nextToken();
                if (arrayStart != JsonToken.START_ARRAY) {
                    throw new IllegalStateException(
                            "Expected 'features' to be an array, got " + arrayStart);
                }
                foundFeatures = true;
                break;
            }
        }
        if (!foundFeatures) {
            throw new IllegalStateException("NREL bulk feed missing 'features' array");
        }

        List<StationRow> batch = new ArrayList<>(BATCH_SIZE);
        int loaded = 0;
        int nextProgressMilestone = PROGRESS_LOG_INTERVAL;

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            // readTree consumes the current Feature object and advances the
            // parser past it; subsequent nextToken() returns either the
            // next Feature's START_OBJECT or END_ARRAY.
            JsonNode feature = objectMapper.readTree(parser);
            StationRow row = parseFeature(feature, fetchStartedAt);
            if (row == null) {
                continue;
            }
            batch.add(row);
            if (batch.size() >= BATCH_SIZE) {
                upsertBatch(batch);
                loaded += batch.size();
                batch.clear();
                if (loaded >= nextProgressMilestone) {
                    log.info("EV station load progress: {} station(s) upserted so far", loaded);
                    nextProgressMilestone = loaded + PROGRESS_LOG_INTERVAL;
                }
            }
        }
        if (!batch.isEmpty()) {
            upsertBatch(batch);
            loaded += batch.size();
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
