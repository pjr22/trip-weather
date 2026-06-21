package com.pjr22.tripweather.service.ai;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.dto.AiAssistDetails;
import com.pjr22.tripweather.dto.AiAssistRequest;
import com.pjr22.tripweather.dto.AiAssistResponse;
import com.pjr22.tripweather.dto.ResolvedWaypoint;
import com.pjr22.tripweather.dto.UnresolvedLocation;
import com.pjr22.tripweather.model.AiProviderConfig;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.AiProviderConfigRepository;
import com.pjr22.tripweather.security.AiKeyCipher;
import com.pjr22.tripweather.security.CurrentUserService;
import com.pjr22.tripweather.service.LocationService;
import com.pjr22.tripweather.service.RouteService;
import com.pjr22.tripweather.service.RouteService.RouteRequest;
import com.pjr22.tripweather.service.ai.LocationListParser.LocationParseException;

/**
 * Orchestrates an AI assist request: prompt the chosen provider, parse the
 * returned locations, geocode each into a waypoint, and calculate a route.
 * AI_ASSIST_PLAN.md, Phase 2.
 *
 * <p>Resilient by design — a single un-geocodable location becomes a warning
 * rather than a failure; fewer than two resolved waypoints returns the
 * waypoints with a warning and no route. The model call gets one repair re-ask
 * if its first output isn't parseable JSON.
 */
@Service
public class AiAssistService {

    private static final Logger logger = LoggerFactory.getLogger(AiAssistService.class);

    /** How many of the top geocode features to consider when name-matching. */
    private static final int GEOCODE_CANDIDATE_LIMIT = 5;
    /** Minimum place-name token overlap to override the geocoder's first result. */
    private static final double NAME_MATCH_MIN_RATIO = 0.34;
    /**
     * ISO-3166-1 alpha-2 codes counted as "United States" — the 50 states + DC
     * (us) plus US territories. Routing is only supported within these; a match
     * anywhere else is surfaced for the user to fix rather than silently routed.
     */
    private static final Set<String> US_COUNTRY_CODES = Set.of("us", "pr", "gu", "vi", "as", "mp", "um");

    private final AiProviderConfigRepository repository;
    private final CurrentUserService currentUserService;
    private final AiKeyCipher keyCipher;
    private final AiChatService chatService;
    private final AssistPromptBuilder promptBuilder;
    private final LocationListParser parser;
    private final LocationService locationService;
    private final RouteService routeService;

    private final int maxWaypoints;
    private final boolean debug;
    private final int geocodeRetries;
    private final int geocodeRetryDelaySeconds;
    private final int geocodeConcurrency;

    public AiAssistService(AiProviderConfigRepository repository,
                           CurrentUserService currentUserService,
                           AiKeyCipher keyCipher,
                           AiChatService chatService,
                           AssistPromptBuilder promptBuilder,
                           LocationListParser parser,
                           LocationService locationService,
                           RouteService routeService,
                           @Value("${trip.ai.max-waypoints:25}") int maxWaypoints,
                           @Value("${trip.ai.assist-debug:false}") boolean debug,
                           @Value("${trip.ai.geocode-retries:1}") int geocodeRetries,
                           @Value("${trip.ai.geocode-retry-delay-seconds:3}") int geocodeRetryDelaySeconds,
                           @Value("${trip.ai.geocode-concurrency:5}") int geocodeConcurrency) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.keyCipher = keyCipher;
        this.chatService = chatService;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.locationService = locationService;
        this.routeService = routeService;
        this.maxWaypoints = maxWaypoints;
        this.debug = debug;
        this.geocodeRetries = geocodeRetries;
        this.geocodeRetryDelaySeconds = geocodeRetryDelaySeconds;
        this.geocodeConcurrency = Math.max(1, geocodeConcurrency);
    }

    public AiAssistResponse assist(AiAssistRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
        }
        if (request.providerConfigId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerConfigId is required");
        }

        User user = currentUserService.currentUser()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "AI provider config not found"));
        AiProviderConfig config = repository.findByIdAndUserId(request.providerConfigId(), user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "AI provider config not found"));

        String apiKey = (config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isBlank())
                ? keyCipher.decrypt(config.getApiKeyEncrypted())
                : null;

        String systemPrompt = promptBuilder.systemPrompt();
        String userPrompt = promptBuilder.userPrompt(request.prompt());

        long startNanos = System.nanoTime();
        ModelRun run = runModel(config, apiKey, systemPrompt, userPrompt);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        List<AiLocation> locations = run.locations();

        List<String> warnings = new ArrayList<>();

        if (locations.size() > maxWaypoints) {
            warnings.add("The assistant suggested " + locations.size() + " locations; using the first "
                    + maxWaypoints + ".");
            locations = locations.subList(0, maxWaypoints);
        }

        List<String> queries = new ArrayList<>(locations.size());
        for (AiLocation loc : locations) {
            queries.add(buildQuery(loc));
        }
        List<GeocodeOutcome> outcomes = geocodeAll(locations, queries);

        List<ResolvedWaypoint> resolved = new ArrayList<>();
        List<UnresolvedLocation> unresolved = new ArrayList<>();
        int outOfAreaCount = 0;
        for (int sequence = 0; sequence < outcomes.size(); sequence++) {
            GeocodeOutcome outcome = outcomes.get(sequence);
            if (outcome.waypoint() != null) {
                resolved.add(outcome.waypoint());
            } else {
                // Both a genuine miss and an out-of-area match become an
                // editable unresolved row; out-of-area also adds the note below.
                unresolved.add(new UnresolvedLocation(sequence, queries.get(sequence)));
                if (outcome.outOfArea()) {
                    outOfAreaCount++;
                }
            }
        }

        if (outOfAreaCount > 0) {
            warnings.add(outOfAreaCount == 1
                    ? "1 suggested stop is outside the United States, which isn't supported — edit or remove it below."
                    : outOfAreaCount + " suggested stops are outside the United States, which isn't supported — "
                            + "edit or remove them below.");
        }

        RouteData route = null;
        if (resolved.size() >= 2) {
            route = routeService.calculateRoute(toRoutingWaypoints(resolved), ZonedDateTime.now(), null);
            if (route == null || route.getGeometry() == null || route.getGeometry().isEmpty()) {
                warnings.add("Could not calculate a route for the resolved locations.");
            }
        } else {
            warnings.add("Need at least 2 locations to build a route; resolved " + resolved.size() + ".");
        }

        logger.info("AI assist for user {} via config {}: {} suggested, {} resolved, {} unresolved, "
                        + "{} warnings, {} ms, {} tokens",
                user.getId(), config.getId(), locations.size(), resolved.size(), unresolved.size(),
                warnings.size(), elapsedMs, run.totalTokens());

        // The debug flag now controls server-side logging only — the prompt and
        // raw output are verbose and app-internal, so they're logged (not
        // returned). The user-facing detail (model, raw response, tokens, time)
        // is always returned below.
        if (debug) {
            logger.info("AI assist debug (config {}): model={} elapsedMs={} tokens(prompt/completion/total)={}/{}/{}"
                            + "\n--- system prompt ---\n{}\n--- user prompt ---\n{}\n--- raw response ---\n{}",
                    config.getId(), config.getModel(), elapsedMs,
                    run.promptTokens(), run.completionTokens(), run.totalTokens(),
                    systemPrompt, userPrompt, run.rawText());
        }

        // Estimated dollar cost — only when the config carries a per-million-token
        // price (and the provider reported the matching token count).
        Double inputCost = cost(config.getInputCostPerMtok(), run.promptTokens());
        Double outputCost = cost(config.getOutputCostPerMtok(), run.completionTokens());
        Double totalCost = (inputCost == null && outputCost == null)
                ? null
                : (inputCost == null ? 0.0 : inputCost) + (outputCost == null ? 0.0 : outputCost);

        AiAssistDetails details = new AiAssistDetails(
                config.getModel(), run.rawText(),
                run.promptTokens(), run.completionTokens(), run.totalTokens(), elapsedMs,
                inputCost, outputCost, totalCost);

        return new AiAssistResponse(resolved, route, unresolved, warnings, details);
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /** Outcome of the model interaction: parsed locations, the raw text they
     *  came from, and token usage summed across the initial + any repair call. */
    private record ModelRun(List<AiLocation> locations, String rawText,
                            Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }

    /**
     * Run the model and parse its output, with one repair re-ask if the first
     * output isn't parseable. Token usage is accumulated across both calls; the
     * returned {@code rawText} is whichever output actually parsed.
     */
    private ModelRun runModel(AiProviderConfig config, String apiKey, String systemPrompt, String userPrompt) {
        AiChatResult first = chatService.complete(
                config.getProvider(), config.getModel(), apiKey, config.getBaseUrl(),
                systemPrompt, userPrompt);
        try {
            return new ModelRun(parser.parse(first.content()), first.content(),
                    first.promptTokens(), first.completionTokens(), first.totalTokens());
        } catch (LocationParseException firstFail) {
            logger.info("AI assist: first parse failed ({}); attempting one repair re-ask",
                    firstFail.getMessage());
            AiChatResult repaired = chatService.complete(
                    config.getProvider(), config.getModel(), apiKey, config.getBaseUrl(),
                    promptBuilder.repairSystemPrompt(), first.content());
            Integer promptTokens = addNullable(first.promptTokens(), repaired.promptTokens());
            Integer completionTokens = addNullable(first.completionTokens(), repaired.completionTokens());
            Integer totalTokens = addNullable(first.totalTokens(), repaired.totalTokens());
            try {
                return new ModelRun(parser.parse(repaired.content()), repaired.content(),
                        promptTokens, completionTokens, totalTokens);
            } catch (LocationParseException secondFail) {
                // Both attempts failed — dump what the model actually returned so
                // the operator can see whether it's prose, a wrong-shaped JSON, a
                // refusal, or a truncated body. This is the only window into a
                // "could not be parsed" 502.
                logger.warn("AI assist: model output unparseable after repair re-ask "
                                + "(first='{}', second='{}'). Raw output: {} | repaired: {}",
                        firstFail.getMessage(), secondFail.getMessage(),
                        AiChatClient.truncate(first.content()), AiChatClient.truncate(repaired.content()));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "The AI response could not be parsed into a list of locations.");
            }
        }
    }

    /** Estimated USD cost for a token count at a per-million-tokens price.
     *  Null when either input is null (price not configured / tokens unreported). */
    private static Double cost(Double pricePerMtok, Integer tokens) {
        if (pricePerMtok == null || tokens == null) {
            return null;
        }
        return tokens / 1_000_000.0 * pricePerMtok;
    }

    /** Sum two nullable token counts, treating null as "unknown" (not zero). */
    private static Integer addNullable(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }

    private static String buildQuery(AiLocation loc) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, loc.name());
        addIfPresent(parts, loc.city());
        addIfPresent(parts, loc.state());
        return String.join(", ", parts);
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    /**
     * Forward-geocode one query and map the best (first) match to a waypoint,
     * retrying on a transient failure. An attempt that <em>errors</em> (Geoapify
     * unreachable / non-2xx) or returns <em>no result</em> (null, or zero
     * features) is retried up to {@code trip.ai.geocode-retries} times with a
     * {@code trip.ai.geocode-retry-delay-seconds} pause between attempts. Returns
     * null only after every attempt fails — the caller turns that into a warning
     * rather than failing the whole request.
     */
    /**
     * Geocode every location concurrently (bounded by
     * {@code trip.ai.geocode-concurrency}), returning outcomes in the original
     * sequence order. The global Geoapify pacer inside {@link LocationService}
     * enforces the actual request rate; this fan-out only overlaps the response
     * waits and per-location retry delays that the old sequential loop summed.
     */
    private List<GeocodeOutcome> geocodeAll(List<AiLocation> locations, List<String> queries) {
        int n = locations.size();
        if (n == 0) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(geocodeConcurrency, n));
        try {
            List<Callable<GeocodeOutcome>> tasks = new ArrayList<>(n);
            for (int sequence = 0; sequence < n; sequence++) {
                final int seq = sequence;
                tasks.add(() -> geocode(queries.get(seq), locations.get(seq).name(), seq));
            }
            List<GeocodeOutcome> outcomes = new ArrayList<>(n);
            for (Future<GeocodeOutcome> future : pool.invokeAll(tasks)) {
                outcomes.add(future.get());
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Geocoding was interrupted.");
        } catch (ExecutionException e) {
            // geocode() handles its own errors and never throws; stay defensive.
            logger.warn("AI assist: a geocoding task failed unexpectedly", e.getCause());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Geocoding failed unexpectedly.");
        } finally {
            pool.shutdown();
        }
    }

    private GeocodeOutcome geocode(String query, String name, int sequence) {
        if (query.isBlank()) {
            return GeocodeOutcome.MISS;
        }
        int totalAttempts = Math.max(0, geocodeRetries) + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            GeocodeOutcome outcome = attemptGeocode(query, name, sequence, attempt, totalAttempts);
            // A resolved waypoint OR a definitive out-of-area match ends the
            // loop; only a transient miss (error / no result) is retried.
            if (outcome.waypoint() != null || outcome.outOfArea()) {
                return outcome;
            }
            if (attempt < totalAttempts && !pauseBeforeRetry()) {
                break; // interrupted — stop retrying
            }
        }
        return GeocodeOutcome.MISS;
    }

    /**
     * One forward-geocode attempt. Returns a resolved waypoint, a transient MISS
     * (error / no usable result — retryable), or OUT_OF_AREA when the best match
     * is positively outside the supported US service area (not retryable).
     */
    private GeocodeOutcome attemptGeocode(String query, String name, int sequence,
                                          int attempt, int totalAttempts) {
        JsonNode response;
        try {
            response = locationService.searchLocations(query);
        } catch (Exception e) {
            logger.warn("Geocode attempt {}/{} errored for \"{}\": {}",
                    attempt, totalAttempts, query, e.getMessage());
            return GeocodeOutcome.MISS;
        }
        if (response == null) {
            logger.info("Geocode attempt {}/{} returned no result for \"{}\"",
                    attempt, totalAttempts, query);
            return GeocodeOutcome.MISS;
        }
        JsonNode features = response.path("features");
        if (!features.isArray() || features.isEmpty()) {
            logger.info("Geocode attempt {}/{} returned no features for \"{}\"",
                    attempt, totalAttempts, query);
            return GeocodeOutcome.MISS;
        }
        JsonNode feature = chooseBestFeature(features, name);
        JsonNode props = feature.path("properties");

        Double lat = readCoordinate(props, "lat");
        Double lon = readCoordinate(props, "lon");
        if (lat == null || lon == null) {
            // Fall back to GeoJSON geometry [lon, lat].
            JsonNode coords = feature.path("geometry").path("coordinates");
            if (coords.isArray() && coords.size() >= 2) {
                lon = coords.get(0).asDouble();
                lat = coords.get(1).asDouble();
            }
        }
        if (lat == null || lon == null) {
            return GeocodeOutcome.MISS;
        }

        String locationName = props.path("formatted").asText(null);
        if (locationName == null || locationName.isBlank()) {
            locationName = query;
        }

        if (!isInServiceArea(props)) {
            logger.info("Geocode attempt {}/{} matched outside the US service area for \"{}\": {}",
                    attempt, totalAttempts, query, locationName);
            return GeocodeOutcome.OUT_OF_AREA;
        }

        String city = props.path("city").asText(null);
        String state = props.path("state_code").asText(props.path("state").asText(null));

        return GeocodeOutcome.resolved(new ResolvedWaypoint(sequence, lat, lon, locationName, city, state, null));
    }

    /** Result of geocoding one location: a resolved waypoint, a transient miss,
     *  or a definitive match outside the supported (US) service area. */
    private record GeocodeOutcome(ResolvedWaypoint waypoint, boolean outOfArea) {
        static final GeocodeOutcome MISS = new GeocodeOutcome(null, false);
        static final GeocodeOutcome OUT_OF_AREA = new GeocodeOutcome(null, true);
        static GeocodeOutcome resolved(ResolvedWaypoint waypoint) {
            return new GeocodeOutcome(waypoint, false);
        }
    }

    /**
     * Whether a geocode feature is within the supported United States service
     * area (50 states + DC + US territories). A country we can't positively
     * determine is treated as in-area, so we only flag features we can place
     * outside the US.
     */
    private static boolean isInServiceArea(JsonNode props) {
        String code = props.path("country_code").asText("");
        if (!code.isBlank()) {
            return US_COUNTRY_CODES.contains(code.toLowerCase(Locale.ROOT));
        }
        String country = props.path("country").asText("");
        if (!country.isBlank()) {
            return country.equalsIgnoreCase("United States");
        }
        return true;
    }

    /**
     * Pick the geocode feature that best matches the AI-suggested place name,
     * rather than blindly taking the first. Geoapify sometimes ranks a generic
     * city / admin area above the actual place when the query carries the city —
     * e.g. "Castlewood Canyon State Park, Franktown, CO" returns Franktown first,
     * with the park as the second/third result. We score the top candidates by
     * how many distinctive tokens of the place name appear in each feature's
     * text and take the best; when nothing meaningfully matches the name (or
     * there's no usable name) we fall back to the geocoder's own first result,
     * so this never does worse than the previous behavior.
     */
    private static JsonNode chooseBestFeature(JsonNode features, String name) {
        JsonNode first = features.get(0);
        List<String> tokens = nameTokens(name);
        if (tokens.isEmpty()) {
            return first;
        }
        int limit = Math.min(features.size(), GEOCODE_CANDIDATE_LIMIT);
        JsonNode best = first;
        double bestScore = -1.0;
        for (int i = 0; i < limit; i++) {
            JsonNode f = features.get(i);
            double score = nameOverlap(f, tokens);
            if (score > bestScore) { // strictly greater → ties keep the earlier (better-ranked) feature
                bestScore = score;
                best = f;
            }
        }
        return bestScore >= NAME_MATCH_MIN_RATIO ? best : first;
    }

    /** Fraction of the place-name tokens that appear in a feature's name/address text (0..1). */
    private static double nameOverlap(JsonNode feature, List<String> tokens) {
        JsonNode props = feature.path("properties");
        String text = (props.path("name").asText("") + " "
                + props.path("formatted").asText("") + " "
                + props.path("address_line1").asText("")).toLowerCase(Locale.ROOT);
        int matched = 0;
        for (String token : tokens) {
            if (text.contains(token)) {
                matched++;
            }
        }
        return tokens.isEmpty() ? 0.0 : (double) matched / tokens.size();
    }

    /** Distinctive tokens of a place name: lowercased alphanumeric runs of length ≥ 3. */
    private static List<String> nameTokens(String name) {
        List<String> tokens = new ArrayList<>();
        if (name == null) {
            return tokens;
        }
        for (String token : name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Sleep the configured retry delay between geocode attempts. Returns false if
     * the thread was interrupted (the caller then stops retrying); a non-positive
     * delay is a no-op that returns true.
     */
    private boolean pauseBeforeRetry() {
        if (geocodeRetryDelaySeconds <= 0) {
            return true;
        }
        long baseMillis = geocodeRetryDelaySeconds * 1000L;
        // Up to +50% random jitter so concurrent retries (parallel fan-out) don't
        // all re-fire at the same instant after the delay.
        long jitter = ThreadLocalRandom.current().nextLong(baseMillis / 2 + 1);
        try {
            Thread.sleep(baseMillis + jitter);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Double readCoordinate(JsonNode props, String field) {
        JsonNode node = props.path(field);
        return node.isNumber() ? node.asDouble() : null;
    }

    private static List<RouteRequest.Waypoint> toRoutingWaypoints(List<ResolvedWaypoint> resolved) {
        List<RouteRequest.Waypoint> waypoints = new ArrayList<>(resolved.size());
        for (ResolvedWaypoint w : resolved) {
            // timezoneName left null — Phase 2 doesn't resolve per-waypoint zones;
            // calculateRoute falls back to the default zone for arrival times.
            waypoints.add(new RouteRequest.Waypoint(
                    w.latitude(), w.longitude(), w.locationName(), null));
        }
        return waypoints;
    }
}
