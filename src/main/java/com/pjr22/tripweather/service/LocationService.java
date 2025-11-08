package com.pjr22.tripweather.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.model.LocationData;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class LocationService {

    private final RestClient restClient;
    private final String apiKey;
    private final String baseUrl;

    public LocationService(@Value("${geoapify.api.key}") String apiKey,
                           @Value("${geoapify.base.url:https://api.geoapify.com/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .build();
    }

    public LocationData reverseGeocode(double latitude, double longitude) {
        String url = String.format("/geocode/reverse?lat=%.6f&lon=%.6f&apiKey=%s", latitude, longitude, apiKey);
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                return null;
            }

            LocationData locationData = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(LocationData.class);

//            if (response != null && response.has("features")) {
//                JsonNode features = response.get("features");
//                if (features.isArray() && features.size() > 0) {
//                    JsonNode firstFeature = features.get(0);
//                    if (firstFeature.has("properties")) {
//                        JsonNode properties = firstFeature.get("properties");
//                        if (properties.has("formatted")) {
//                            return properties.get("formatted").asText();
//                        }
//                    }
//                }
//            }

            return locationData;
         } catch (Exception e) {
            log.info("Failed to get formatted location info from: {}", url);
            log.error("Reverse GeoCode request failed.", e);
            return null;
        }
    }

    public JsonNode searchLocations(String searchText) {
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                return null;
            }

            String url = String.format("/geocode/search?apiKey=%s&text=%s",
                    apiKey, searchText);

            JsonNode response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(JsonNode.class);

            return response;
        } catch (Exception e) {
            log.error("GeoCode search request failed.", e);
            return null;
        }
    }
}
