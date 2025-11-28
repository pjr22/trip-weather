package com.pjr22.tripweather.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * DTO for requesting EV charging stations along a route from the NREL API
 */
@Data
public class EVChargingStationRequest {
    
    private List<List<Double>> route; // List of [longitude, latitude] pairs
    
    // Additional parameters to pass to the NREL API
    private Map<String, Object> parameters;
}