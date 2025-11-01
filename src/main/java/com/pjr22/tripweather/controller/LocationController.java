package com.pjr22.tripweather.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.service.LocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/location")
public class LocationController {

    private final LocationService locationService;

    @Autowired
    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/reverse")
    public Map<String, String> reverseGeocode(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        
        String locationName = locationService.reverseGeocode(latitude, longitude);
        
        Map<String, String> response = new HashMap<>();
        response.put("locationName", locationName);
        return response;
    }

    @GetMapping("/search")
    public JsonNode searchLocations(@RequestParam String query) {
        return locationService.searchLocations(query);
    }
}
