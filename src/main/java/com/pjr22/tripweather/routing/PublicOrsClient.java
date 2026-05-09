package com.pjr22.tripweather.routing;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Public OpenRouteService at {@code api.openrouteservice.org}, authenticated
 * via the {@code Authorization} header. Always present (the public engine is
 * the safety net when the local engine is missing, disabled, or out of
 * coverage).
 */
@Component
public class PublicOrsClient implements OrsClient {

    private final RestClient restClient;
    private final String apiKey;

    public PublicOrsClient(RestClient orsRestClient,
                           @Value("${openrouteservice.api.key}") String apiKey) {
        this.restClient = orsRestClient;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public JsonNode post(String path, Object body) {
        return restClient.post()
                .uri(path)
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    @Override
    public JsonNode get(String path) {
        return restClient.get()
                .uri(path)
                .header("Authorization", apiKey)
                .retrieve()
                .body(JsonNode.class);
    }

    @Override
    public String name() {
        return "public";
    }
}
