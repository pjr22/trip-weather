package com.pjr22.tripweather.service.ai;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.dto.AiAssistRequest;
import com.pjr22.tripweather.dto.AiAssistResponse;
import com.pjr22.tripweather.dto.ResolvedWaypoint;
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
                           @Value("${trip.ai.geocode-retry-delay-seconds:3}") int geocodeRetryDelaySeconds) {
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

        String rawResponse = chatService.complete(
                config.getProvider(), config.getModel(), apiKey, config.getBaseUrl(),
                systemPrompt, userPrompt);

        List<AiLocation> locations = parseWithRepair(config, apiKey, rawResponse);

        List<String> warnings = new ArrayList<>();

        if (locations.size() > maxWaypoints) {
            warnings.add("The assistant suggested " + locations.size() + " locations; using the first "
                    + maxWaypoints + ".");
            locations = locations.subList(0, maxWaypoints);
        }

        List<ResolvedWaypoint> resolved = new ArrayList<>();
        for (AiLocation loc : locations) {
            String query = buildQuery(loc);
            ResolvedWaypoint waypoint = geocode(query);
            if (waypoint == null) {
                warnings.add("Couldn't find a location for \"" + query + "\".");
            } else {
                resolved.add(waypoint);
            }
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

        logger.info("AI assist for user {} via config {}: {} suggested, {} resolved, {} warnings",
                user.getId(), config.getId(), locations.size(), resolved.size(), warnings.size());

        return new AiAssistResponse(
                resolved,
                route,
                warnings,
                debug ? ("SYSTEM:\n" + systemPrompt + "\n\nUSER:\n" + userPrompt) : null,
                debug ? rawResponse : null);
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private List<AiLocation> parseWithRepair(AiProviderConfig config, String apiKey, String rawResponse) {
        try {
            return parser.parse(rawResponse);
        } catch (LocationParseException first) {
            logger.info("AI assist: first parse failed ({}); attempting one repair re-ask",
                    first.getMessage());
            String repaired = chatService.complete(
                    config.getProvider(), config.getModel(), apiKey, config.getBaseUrl(),
                    promptBuilder.repairSystemPrompt(), rawResponse);
            try {
                return parser.parse(repaired);
            } catch (LocationParseException second) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "The AI response could not be parsed into a list of locations.");
            }
        }
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
    private ResolvedWaypoint geocode(String query) {
        if (query.isBlank()) {
            return null;
        }
        int totalAttempts = Math.max(0, geocodeRetries) + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            ResolvedWaypoint waypoint = attemptGeocode(query, attempt, totalAttempts);
            if (waypoint != null) {
                return waypoint;
            }
            if (attempt < totalAttempts && !pauseBeforeRetry()) {
                break; // interrupted — stop retrying
            }
        }
        return null;
    }

    /** One forward-geocode attempt. Returns null on error or no usable result. */
    private ResolvedWaypoint attemptGeocode(String query, int attempt, int totalAttempts) {
        JsonNode response;
        try {
            response = locationService.searchLocations(query);
        } catch (Exception e) {
            logger.warn("Geocode attempt {}/{} errored for \"{}\": {}",
                    attempt, totalAttempts, query, e.getMessage());
            return null;
        }
        if (response == null) {
            logger.info("Geocode attempt {}/{} returned no result for \"{}\"",
                    attempt, totalAttempts, query);
            return null;
        }
        JsonNode features = response.path("features");
        if (!features.isArray() || features.isEmpty()) {
            logger.info("Geocode attempt {}/{} returned no features for \"{}\"",
                    attempt, totalAttempts, query);
            return null;
        }
        JsonNode feature = features.get(0);
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
            return null;
        }

        String locationName = props.path("formatted").asText(null);
        if (locationName == null || locationName.isBlank()) {
            locationName = query;
        }
        String city = props.path("city").asText(null);
        String state = props.path("state_code").asText(props.path("state").asText(null));

        return new ResolvedWaypoint(lat, lon, locationName, city, state, null);
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
        try {
            Thread.sleep(geocodeRetryDelaySeconds * 1000L);
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
