package com.pjr22.tripweather.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.model.LocationData;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Simplifies a location name by removing zip code and country information.
     * Keeps the address up to and including the state abbreviation.
     * 
     * @param locationData LocationData object containing location information
     * @return Simplified location name (e.g., "99 West 12th Avenue, Denver, CO")
     *         or original formatted name if no state is found, or null if locationData is invalid
     */
    public String getSimplifiedLocationName(LocationData locationData) {
        if (locationData == null || locationData.getFeatures() == null || locationData.getFeatures().isEmpty()) {
            return null;
        }

        String formatted = locationData.getFeatures().get(0).getProperties().getFormatted();
        if (formatted == null || formatted.trim().isEmpty()) {
            return null;
        }

        // Pattern to match state abbreviation (2 uppercase letters) followed by optional space and numbers/characters
        // This will match "CO 80204" or "CO" and stop there
        Pattern pattern = Pattern.compile(",\\s*([A-Z]{2})(?:\\s+[^,]+)?$");
        Matcher matcher = pattern.matcher(formatted);

        if (matcher.find()) {
            // Find the end position of the state abbreviation
            int stateEnd = matcher.end(1);
            return formatted.substring(0, stateEnd);
        }

        // If no state pattern is found, try a simpler approach - look for 2-letter state code
        Pattern statePattern = Pattern.compile(",\\s*([A-Z]{2})");
        Matcher stateMatcher = statePattern.matcher(formatted);
        
        if (stateMatcher.find()) {
            int stateEnd = stateMatcher.end(1);
            return formatted.substring(0, stateEnd);
        }

        // If no state is found, return the original formatted string
        return formatted;
    }
}
