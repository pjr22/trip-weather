package com.pjr22.tripweather.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.service.LocationService;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/location")
@Validated
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/reverse")
    public LocationData reverseGeocode(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double longitude) {

        return locationService.reverseGeocode(latitude, longitude);
    }

    @GetMapping("/search")
    public JsonNode searchLocations(@RequestParam String query) {
        return locationService.searchLocations(query);
    }
    
}
