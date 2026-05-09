package com.pjr22.tripweather.routing;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Thin HTTP wrapper around an OpenRouteService instance — public or local.
 * Both implementations share the same call shapes; only the base URL and the
 * (optional) {@code Authorization} header differ. The dispatch wrapper in
 * RouteService picks one at runtime based on coverage + config + the
 * outcome of any local-engine attempt.
 */
public interface OrsClient {

    JsonNode post(String path, Object body) throws Exception;

    JsonNode get(String path) throws Exception;

    /** Tag value for metrics ("local" / "public"). */
    String name();
}
