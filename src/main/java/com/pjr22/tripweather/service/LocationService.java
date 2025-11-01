package com.pjr22.tripweather.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class LocationService {

    private final RestClient restClient;
    private final String apiKey;
    
    private static final String BASE_URL = "https://api.openrouteservice.org";

    public LocationService(@Value("${openrouteservice.api.key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    public String reverseGeocode(double latitude, double longitude) {
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                return null;
            }

            String url = String.format("/geocode/reverse?api_key=%s&point.lon=%.6f&point.lat=%.6f&layers=coarse",
                    apiKey, longitude, latitude);

            JsonNode response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(JsonNode.class);

            if (response != null && response.has("features")) {
                JsonNode features = response.get("features");
                if (features.isArray() && features.size() > 0) {
                    JsonNode firstFeature = features.get(0);
                    if (firstFeature.has("properties")) {
                        JsonNode properties = firstFeature.get("properties");
                        if (properties.has("label")) {
                            return properties.get("label").asText();
                        }
                    }
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public JsonNode searchLocations(String searchText) {
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                return null;
            }

            String url = String.format("/geocode/search?api_key=%s&text=%s",
                    apiKey, searchText);

            JsonNode response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(JsonNode.class);

            return response;
        } catch (Exception e) {
            return null;
        }
    }
}
