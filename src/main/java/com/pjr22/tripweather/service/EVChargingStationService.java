package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.EVChargingStationRequest;
import com.pjr22.tripweather.dto.EVChargingStationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Service for interacting with the NREL EV Charging Stations API
 */
@Service
@Slf4j
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
                .messageConverters(converters -> converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()))
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
            
            log.info("Making request to NREL EV charging stations API");
            log.info("Route WKT: {}...", routeWkt.subSequence(0, 80));
            log.info("Request parameters: {}", request.getParameters());
            
            // Build the URI with only the API key (all parameters will be in request body)
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromPath("/api/alt-fuel-stations/v1/nearby-route.geojson")
                    .queryParam("api_key", nrelApiKey);
            
            String requestUrl = uriBuilder.build().toUriString();
            log.info("NREL API request URL: {}", requestUrl);
            
            // Create request body with route data and all parameters
            Map<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("route", routeWkt);
            
            // Add all parameters to request body (including route parameters)
            if (request.getParameters() != null) {
                for (Map.Entry<String, Object> entry : request.getParameters().entrySet()) {
                    if (entry.getValue() != null) {
                        requestBody.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            
            log.info("NREL API request body: {}", requestBody);
            
            // Make the POST request to NREL API with route in request body
            EVChargingStationResponse response = restClient.post()
                  .uri(requestUrl)
                  .header("Content-Type", MediaType.APPLICATION_JSON.toString())
                  .header("Accept", MediaType.APPLICATION_JSON.toString())
                  .body(requestBody)
                  .retrieve()
                  .body(EVChargingStationResponse.class);
            
            log.info("NREL API response received");
            log.info("Response type: {}", response != null ? response.getType() : "null");
            log.info("Number of features: {}", response != null && response.getFeatures() != null ? response.getFeatures().size() : 0);
            
            return response;
            
        } catch (Exception e) {
            log.error("Error calling NREL EV charging stations API", e);
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
        
        StringBuilder wktBuilder = new StringBuilder("LINESTRING (");
        
        for (int i = 0; i < route.size(); i++) {
            java.util.List<Double> point = route.get(i);
            if (point == null || point.size() < 2) {
                throw new IllegalArgumentException("Invalid route point at index " + i);
            }
            
            // WKT format is "longitude latitude" (note the space, not comma)
            wktBuilder.append(point.get(0)).append(" ").append(point.get(1));
            
            if (i < route.size() - 1) {
                wktBuilder.append(", ");
            }
        }
        
        wktBuilder.append(")");
        return wktBuilder.toString();
    }
}