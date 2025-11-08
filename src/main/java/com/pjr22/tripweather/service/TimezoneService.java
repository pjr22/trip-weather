package com.pjr22.tripweather.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class TimezoneService {

    // Cache for timezone lookups to avoid repeated API calls
    private final Map<String, String> timezoneCache = new HashMap<>();
    
    public TimezoneService(@Value("${timezone.api.baseUrl:https://api.timezonedb.com/v2.1}") String baseUrl) {
        // baseUrl parameter kept for potential future API integration
    }

    /**
     * Get timezone for given coordinates using a simple fallback approach
     * For now, we'll use a basic longitude-based timezone approximation
     * In a production environment, you'd want to use a proper timezone API
     */
    public String getTimezone(Double latitude, Double longitude) {
        // Create a cache key from coordinates
        String cacheKey = latitude.toString() + "," + longitude.toString();
        
        // Check cache first
        if (timezoneCache.containsKey(cacheKey)) {
            return timezoneCache.get(cacheKey);
        }
        
        // Simple timezone approximation based on longitude
        // This is a rough approximation - in production you'd use a proper API
        String timezone = approximateTimezoneFromLongitude(longitude);
        
        // Cache the result
        timezoneCache.put(cacheKey, timezone);
        
        return timezone;
    }
    
    /**
     * Simple timezone approximation based on longitude
     * This is a fallback method when no API key is available
     */
    private String approximateTimezoneFromLongitude(Double longitude) {
        // Approximate timezone based on longitude (each 15 degrees ≈ 1 hour)
        int offset = (int) Math.round(longitude / 15.0);
        
        // Map common US timezones based on longitude ranges
        if (longitude >= -125 && longitude < -115) {
            return "America/Los_Angeles"; // PST
        } else if (longitude >= -115 && longitude < -105) {
            return "America/Denver"; // MST
        } else if (longitude >= -105 && longitude < -90) {
            return "America/Chicago"; // CST
        } else if (longitude >= -90 && longitude < -75) {
            return "America/New_York"; // EST
        } else if (longitude >= -75 && longitude < -65) {
            return "America/Halifax"; // AST
        } else {
            // Default to UTC or use the calculated offset
            if (offset >= -12 && offset <= 14) {
                return "GMT" + (offset >= 0 ? "+" : "") + offset;
            } else {
                return "UTC";
            }
        }
    }
    
    /**
     * Convert a datetime string from one timezone to another
     */
    public String convertDateTime(String dateTimeStr, String fromTimezone, String toTimezone) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr, formatter);
            
            ZoneId fromZoneId = ZoneId.of(fromTimezone);
            ZoneId toZoneId = ZoneId.of(toTimezone);
            
            ZonedDateTime fromZonedDateTime = localDateTime.atZone(fromZoneId);
            ZonedDateTime toZonedDateTime = fromZonedDateTime.withZoneSameInstant(toZoneId);
            
            return toZonedDateTime.format(formatter);
        } catch (Exception e) {
            System.err.println("Error converting datetime from " + fromTimezone + " to " + toTimezone + ": " + e.getMessage());
            return dateTimeStr; // Return original if conversion fails
        }
    }
    
    /**
     * Add minutes to a datetime string in the specified timezone
     */
    public String addMinutesToDateTime(String dateTimeStr, String timezone, Integer minutes) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr, formatter);
            
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime zonedDateTime = localDateTime.atZone(zoneId);
            ZonedDateTime resultZonedDateTime = zonedDateTime.plusMinutes(minutes);
            
            return resultZonedDateTime.format(formatter);
        } catch (Exception e) {
            System.err.println("Error adding minutes to datetime: " + e.getMessage());
            return dateTimeStr; // Return original if addition fails
        }
    }
    
    /**
     * Get current time in the specified timezone
     */
    public String getCurrentTime(String timezone) {
        try {
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            return now.format(formatter);
        } catch (Exception e) {
            System.err.println("Error getting current time for timezone " + timezone + ": " + e.getMessage());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            return LocalDateTime.now().format(formatter);
        }
    }
    
    /**
     * Validate if a timezone identifier is valid
     */
    public boolean isValidTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get timezone abbreviation (3-letter code) considering daylight savings time
     */
    public String getTimezoneAbbreviation(String timezoneId, String dateTimeStr) {
        try {
            ZoneId zoneId = ZoneId.of(timezoneId);
            ZonedDateTime zonedDateTime;
            
            if (dateTimeStr != null && !dateTimeStr.isEmpty()) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr, formatter);
                zonedDateTime = localDateTime.atZone(zoneId);
            } else {
                zonedDateTime = ZonedDateTime.now(zoneId);
            }
            
            // Get the timezone abbreviation that accounts for DST
            return zonedDateTime.getZone().getRules().isDaylightSavings(zonedDateTime.toInstant()) 
                ? getDaylightAbbreviation(timezoneId)
                : getStandardAbbreviation(timezoneId);
        } catch (Exception e) {
            System.err.println("Error getting timezone abbreviation for " + timezoneId + ": " + e.getMessage());
            return timezoneId.split("/").length > 1 ? timezoneId.split("/")[1].replace("_", " ") : timezoneId;
        }
    }
    
    /**
     * Get standard time abbreviation for common US timezones
     */
    private String getStandardAbbreviation(String timezoneId) {
        switch (timezoneId) {
            case "America/Los_Angeles":
                return "PST";
            case "America/Denver":
                return "MST";
            case "America/Chicago":
                return "CST";
            case "America/New_York":
                return "EST";
            case "America/Halifax":
                return "AST";
            case "America/Phoenix":
                return "MST"; // Phoenix doesn't observe DST
            case "America/Anchorage":
                return "AKST";
            case "Pacific/Honolulu":
                return "HST";
            case "America/Vancouver":
                return "PST";
            case "America/Winnipeg":
                return "CST";
            case "America/Toronto":
                return "EST";
            case "America/Montreal":
                return "EST";
            case "America/Mexico_City":
                return "CST";
            default:
                // For other timezones, try to extract a reasonable abbreviation
                if (timezoneId.contains("/")) {
                    String city = timezoneId.split("/")[1].replace("_", " ");
                    return city.length() >= 3 ? city.substring(0, 3).toUpperCase() : city.toUpperCase();
                }
                return timezoneId.length() >= 3 ? timezoneId.substring(0, 3).toUpperCase() : timezoneId.toUpperCase();
        }
    }
    
    /**
     * Get daylight savings time abbreviation for common US timezones
     */
    private String getDaylightAbbreviation(String timezoneId) {
        switch (timezoneId) {
            case "America/Los_Angeles":
                return "PDT";
            case "America/Denver":
                return "MDT";
            case "America/Chicago":
                return "CDT";
            case "America/New_York":
                return "EDT";
            case "America/Halifax":
                return "ADT";
            case "America/Anchorage":
                return "AKDT";
            case "America/Vancouver":
                return "PDT";
            case "America/Winnipeg":
                return "CDT";
            case "America/Toronto":
                return "EDT";
            case "America/Montreal":
                return "EDT";
            case "America/Mexico_City":
                return "CDT";
            default:
                // For other timezones, fall back to standard abbreviation
                return getStandardAbbreviation(timezoneId);
        }
    }
}
