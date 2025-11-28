package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.EVChargingStationRequest;
import com.pjr22.tripweather.dto.EVChargingStationResponse;
import com.pjr22.tripweather.service.EVChargingStationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for EV charging station endpoints
 */
@RestController
@RequestMapping("/api/ev-charging")
@CrossOrigin(origins = "*")
@Slf4j
public class EVChargingStationController {

    private final EVChargingStationService evChargingStationService;

    public EVChargingStationController(EVChargingStationService evChargingStationService) {
        this.evChargingStationService = evChargingStationService;
    }

    /**
     * Get EV charging stations along a route
     * 
     * @param request The request containing route coordinates and additional parameters
     * @return EV charging stations along the route
     */
    @PostMapping("/stations")
    public ResponseEntity<EVChargingStationResponse> getStationsAlongRoute(
            @RequestBody EVChargingStationRequest request) {
        
        log.info("Received request for EV charging stations along route with {} points", 
                request.getRoute() != null ? request.getRoute().size() : 0);
        
        try {
            EVChargingStationResponse response = evChargingStationService.getStationsAlongRoute(request);
            
            if (response != null) {
                log.info("Successfully retrieved {} EV charging stations", 
                        response.getFeatures() != null ? response.getFeatures().size() : 0);
                return ResponseEntity.ok(response);
            } else {
                log.warn("Received null response from EV charging station service");
                return ResponseEntity.internalServerError().build();
            }
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid request for EV charging stations: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error retrieving EV charging stations", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Health check endpoint for the EV charging service
     * 
     * @return Health status
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("EV charging station service is healthy");
    }
}