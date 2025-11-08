package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.service.TimezoneService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/timezone")
public class TimezoneController {

    private final TimezoneService timezoneService;

    public TimezoneController(TimezoneService timezoneService) {
        this.timezoneService = timezoneService;
    }

    @GetMapping
    public Map<String, String> getTimezone(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(required = false) String dateTime) {
        
        String timezoneId = timezoneService.getTimezone(latitude, longitude);
        String timezoneAbbr = timezoneService.getTimezoneAbbreviation(timezoneId, dateTime);
        
        Map<String, String> response = new HashMap<>();
        response.put("timezone", timezoneId);
        response.put("abbreviation", timezoneAbbr);
        return response;
    }
}
