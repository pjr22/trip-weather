package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.model.WeatherData;
import com.pjr22.tripweather.service.WeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherService weatherService;

    @Autowired
    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/forecast")
    public WeatherData getWeatherForecast(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam String date,
            @RequestParam String time) {
        
        return weatherService.getWeatherForecast(latitude, longitude, date, time);
    }
}
