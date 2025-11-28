package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.EVChargingStationRequest;
import com.pjr22.tripweather.dto.EVChargingStationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Service for interacting with the NREL EV Charging Stations API
 */
@Service
public class EVChargingStationService {

    private final RestClient restClient;
    private final String nrelBaseUrl;
    private final String nrelApiKey;

    public EVChargingStationService(
            @Value("${nrel.base.url}") String nrelBaseUrl,
            @Value("${nrel.api.key}") String nrelApiKey) {
        this.nrelBaseUrl = nrelBaseUrl;
        this.nrelApiKey = nrelApiKey;
        this.restClient = RestClient.builder()
                .baseUrl(nrelBaseUrl)
                .build();
    }

    /**
     * Get EV charging stations along a route
     * 
     * @param request The request containing route coordinates and additional parameters
     * @return EV charging stations along the route
     */
    public EVChargingStationResponse getStationsAlongRoute(EVChargingStationRequest request) {
        try {
            // Convert route coordinates to Well Known Text LINESTRING format
            String routeWkt = convertRouteToWkt(request.getRoute());
            
            // Build the URI with query parameters
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromPath("/api/alt-fuel-stations/v1/nearby-route.geojson")
                    .queryParam("api_key", nrelApiKey)
                    .queryParam("route", routeWkt);
            
            // Add additional parameters from the request
            if (request.getParameters() != null) {
                for (Map.Entry<String, Object> entry : request.getParameters().entrySet()) {
                    if (entry.getValue() != null) {
                        uriBuilder.queryParam(entry.getKey(), entry.getValue());
                    }
                }
            }
            
            // Make the GET request to NREL API
            EVChargingStationResponse response = restClient.get()
                    .uri(uriBuilder.build().toUriString())
                    .retrieve()
                    .body(EVChargingStationResponse.class);
            
            return response;
            
        } catch (Exception e) {
            // Create an error response
            EVChargingStationResponse errorResponse = new EVChargingStationResponse();
            errorResponse.setType("FeatureCollection");
            
            // You could add more detailed error handling here if needed
            return errorResponse;
        }
    }
    
    /**
     * Convert a list of [longitude, latitude] pairs to Well Known Text LINESTRING format
     * 
     * @param route List of [longitude, latitude] pairs
     * @return WKT LINESTRING format
     */
    private String convertRouteToWkt(java.util.List<java.util.List<Double>> route) {
        if (route == null || route.isEmpty()) {
            throw new IllegalArgumentException("Route cannot be null or empty");
        }
        
        StringBuilder wktBuilder = new StringBuilder("LINESTRING(");
        
        for (int i = 0; i < route.size(); i++) {
            java.util.List<Double> point = route.get(i);
            if (point == null || point.size() < 2) {
                throw new IllegalArgumentException("Invalid route point at index " + i);
            }
            
            // WKT format is "longitude latitude" (note the space, not comma)
            wktBuilder.append(point.get(0)).append(" ").append(point.get(1));
            
            if (i < route.size() - 1) {
                wktBuilder.append(",");
            }
        }
        
        wktBuilder.append(")");
        return wktBuilder.toString();
    }
}