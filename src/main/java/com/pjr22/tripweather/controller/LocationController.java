package com.pjr22.tripweather.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.model.LocationData.Timezone;
import com.pjr22.tripweather.service.LocationService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/location")
@Slf4j
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/reverse")
    public Map<String, String> reverseGeocode(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        
        LocationData data = locationService.reverseGeocode(latitude, longitude);
        
        Map<String, String> response = new HashMap<>();
        if (data.getFeatures() == null || data.getFeatures().isEmpty()) {
           log.warn("No locations found at LAT {}, LON {}", latitude, longitude);
           response.put("locationName", "Unknown");
           response.put("zoneStandard", "UNK");
           response.put("offsetStandard", "-00:00");
           response.put("zoneDaylight", "UNK");
           response.put("offsetDaylight", "-00:00");

           return response;
        }
        
        try {
           response.put("locationName", data.getFeatures().get(0).getProperties().getFormatted());
        } catch (Exception e) {
           log.error("Failed to find location name in locationData: {}", data);
           response.put("locationName", "Unknown");
        }

        try {
           Timezone timezone = data.getFeatures().get(0).getProperties().getTimezone();
           response.put("zoneStandard", timezone.getAbbreviationStd());
           response.put("offsetStandard", timezone.getOffsetStd());
           response.put("zoneDaylight", timezone.getAbbreviationDst());
           response.put("offsetDaylight", timezone.getOffsetDst());
        } catch (Exception e) {
           log.error("Failed to find timezone in locationData: {}", data);
           response.put("zoneStandard", "UNK");
           response.put("offsetStandard", "-00:00");
           response.put("zoneDaylight", "UNK");
           response.put("offsetDaylight", "-00:00");
        }

        return response;
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
