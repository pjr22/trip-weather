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
     * Generates a location name from address components.
     * Combines addressLine1, addressLine2, city, and state_code to create a formatted location name.
     * 
     * @param properties LocationData.Properties object containing address information
     * @return Generated location name (e.g., "99 West 12th Avenue, Denver, CO")
     *         or null if properties is invalid
     */
    public String generateLocationName(LocationData.Properties properties) {
        if (properties == null) {
            return null;
        }

        StringBuilder locationName = new StringBuilder();
        
        // Add addressLine1 if available
        if (properties.getAddressLine1() != null && !properties.getAddressLine1().trim().isEmpty()) {
            locationName.append(properties.getAddressLine1().trim());
        }
        
        // Add city if available
        if (properties.getCity() != null && !properties.getCity().trim().isEmpty()) {
            if (locationName.length() > 0) {
                locationName.append(", ");
            }
            locationName.append(properties.getCity().trim());
        }
        
        // Add state_code if available
        if (properties.getStateCode() != null && !properties.getStateCode().trim().isEmpty()) {
            if (locationName.length() > 0) {
                locationName.append(", ");
            }
            locationName.append(properties.getStateCode().trim());
        }
        
        // If we still don't have anything, try using addressLine2 as a fallback
        if (locationName.length() == 0 && properties.getAddressLine2() != null && !properties.getAddressLine2().trim().isEmpty()) {
            locationName.append(properties.getAddressLine2().trim());
        }
        
        // Final fallback to formatted field if nothing else worked
        if (locationName.length() == 0 && properties.getFormatted() != null && !properties.getFormatted().trim().isEmpty()) {
            locationName.append(properties.getFormatted().trim());
        }
        
        return locationName.length() > 0 ? locationName.toString() : null;
    }
}
