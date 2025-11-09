package com.pjr22.tripweather.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.service.LocationService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/location")
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/reverse")
    public LocationData reverseGeocode(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        
        return locationService.reverseGeocode(latitude, longitude);
    }

    @GetMapping("/geocode/reverse")
    public LocationData reverseGeocodeComplete(
            @RequestParam double lat,
            @RequestParam double lon) {
        
        return locationService.reverseGeocode(lat, lon);
    }

    @GetMapping("/search")
    public JsonNode searchLocations(@RequestParam String query) {
        return locationService.searchLocations(query);
    }
    
}
