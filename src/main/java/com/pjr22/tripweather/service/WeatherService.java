package com.pjr22.tripweather.service;

import com.pjr22.tripweather.model.WeatherData;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;

@Service
public class WeatherService {

    private final RestClient restClient;
    private static final String BASE_URL = "https://api.weather.gov";
    private static final String USER_AGENT = "TripWeather/1.0 (tripweather.app)";

    public WeatherService() {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    public WeatherData getWeatherForecast(double latitude, double longitude, String date, String time) {
        try {
            String forecastUrl = getForecastUrl(latitude, longitude);
            if (forecastUrl == null) {
                return WeatherData.createError("Unable to get forecast URL for location");
            }

            JsonNode forecastData = restClient.get()
                    .uri(forecastUrl)
                    .retrieve()
                    .body(JsonNode.class);

            if (forecastData == null || !forecastData.has("properties")) {
                return WeatherData.createError("Invalid forecast data");
            }

            LocalDateTime targetDateTime = parseDateTime(date, time);
            JsonNode periods = forecastData.get("properties").get("periods");
            
            JsonNode matchingPeriod = findMatchingPeriod(periods, targetDateTime);
            
            if (matchingPeriod == null) {
                return WeatherData.createError("No forecast available for selected date/time");
            }

            return extractWeatherData(matchingPeriod);

        } catch (Exception e) {
            return WeatherData.createError("Error fetching weather: " + e.getMessage());
        }
    }

    private String getForecastUrl(double latitude, double longitude) {
        try {
            String pointsUrl = String.format("/points/%.4f,%.4f", latitude, longitude);
            
            JsonNode pointsData = restClient.get()
                    .uri(pointsUrl)
                    .retrieve()
                    .body(JsonNode.class);

            if (pointsData != null && pointsData.has("properties")) {
                JsonNode properties = pointsData.get("properties");
                if (properties.has("forecast")) {
                    return properties.get("forecast").asText();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(String date, String time) {
        String dateTimeStr = date + "T" + time;
        return LocalDateTime.parse(dateTimeStr);
    }

    private JsonNode findMatchingPeriod(JsonNode periods, LocalDateTime targetDateTime) {
        if (periods == null || !periods.isArray()) {
            return null;
        }

        for (JsonNode period : periods) {
            if (period.has("startTime") && period.has("endTime")) {
                ZonedDateTime startTime = ZonedDateTime.parse(period.get("startTime").asText());
                ZonedDateTime endTime = ZonedDateTime.parse(period.get("endTime").asText());
                
                ZonedDateTime targetZoned = targetDateTime.atZone(startTime.getZone());
                
                if (!targetZoned.isBefore(startTime) && targetZoned.isBefore(endTime)) {
                    return period;
                }
            }
        }

        if (periods.size() > 0) {
            return periods.get(0);
        }

        return null;
    }

    private WeatherData extractWeatherData(JsonNode period) {
        String condition = period.has("shortForecast") ? 
                period.get("shortForecast").asText() : "Unknown";
        
        Integer temperature = period.has("temperature") ? 
                period.get("temperature").asInt() : null;
        
        String temperatureUnit = period.has("temperatureUnit") ? 
                period.get("temperatureUnit").asText() : "F";
        
        String windSpeed = period.has("windSpeed") ? 
                period.get("windSpeed").asText() : "Unknown";
        
        String windDirection = period.has("windDirection") ? 
                period.get("windDirection").asText() : "Unknown";

        return new WeatherData(condition, temperature, temperatureUnit, windSpeed, windDirection);
    }
}
