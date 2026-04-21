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

    private final RouteService routeService;
    private final RestClient restClient;
    private final String apiKey;
    private final String baseUrl;

    public LocationService(
          @Value("${geoapify.api.key}") String apiKey,
          @Value("${geoapify.base.url:https://api.geoapify.com/v1}") String baseUrl,
          RouteService routeService
    ) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.routeService = routeService;
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .build();
    }

    public LocationData reverseGeocode(double latitude, double longitude) {
        requireApiKey();
        String path = String.format("/geocode/reverse?lat=%.6f&lon=%.6f&apiKey=%s", latitude, longitude, apiKey);

        Double elevation = routeService.getElevation(latitude, longitude);
        LocationData locationData = restClient.get()
                .uri(path)
                .retrieve()
                .body(LocationData.class);

        if (elevation != null && locationData != null
                && locationData.getFeatures() != null
                && !locationData.getFeatures().isEmpty()) {
            locationData.getFeatures().get(0).getGeometry().getCoordinates().add(elevation);
        }

        return locationData;
    }

    public JsonNode searchLocations(String searchText) {
        requireApiKey();
        String path = String.format("/geocode/search?apiKey=%s&text=%s", apiKey, searchText);
        return restClient.get()
                .uri(path)
                .retrieve()
                .body(JsonNode.class);
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()
                || apiKey.startsWith("set with ")) {
            // The default value in application.properties is a placeholder string; treat
            // that as unconfigured too.
            throw new IllegalStateException(
                    "GEOAPIFY_API_KEY environment variable is not set. "
                  + "Location services are unavailable until it is configured.");
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
