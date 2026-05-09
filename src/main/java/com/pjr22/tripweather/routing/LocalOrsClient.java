package com.pjr22.tripweather.routing;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Local OpenRouteService instance on the same Docker network. No
 * Authorization header — the container exposes its API unauthenticated on a
 * non-published port. Bean is only created when {@code trip.local.ors.enabled=true},
 * so the dispatch wrapper sees the absence (rather than a misconfigured
 * client) and short-circuits to public.
 *
 * <p>Per-call timeouts are wired into the underlying RestClient by
 * {@code HttpClientConfig.localOrsRestClient}. Expiry surfaces as a
 * {@link org.springframework.web.client.ResourceAccessException} which the
 * dispatch wrapper treats as a fallback trigger.
 */
@Component
@ConditionalOnProperty(name = "trip.local.ors.enabled", havingValue = "true")
public class LocalOrsClient implements OrsClient {

    private final RestClient restClient;

    public LocalOrsClient(RestClient localOrsRestClient) {
        this.restClient = localOrsRestClient;
    }

    @Override
    public JsonNode post(String path, Object body) {
        return restClient.post()
                .uri(path)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    @Override
    public JsonNode get(String path) {
        return restClient.get()
                .uri(path)
                .retrieve()
                .body(JsonNode.class);
    }

    @Override
    public String name() {
        return "local";
    }
}
