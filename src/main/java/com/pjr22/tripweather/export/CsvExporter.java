package com.pjr22.tripweather.export;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.stereotype.Component;

import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.model.WeatherData;

@Component
public class CsvExporter implements RouteExporter {

    private static final double METERS_TO_FEET = 3.28084;
    private static final double METERS_TO_MILES = 0.0006213712;

    // Excel relies on a UTF-8 BOM to open CSVs with non-ASCII names correctly.
    private static final char UTF8_BOM = '﻿';

    private static final String[] HEADERS = {
            "sequence", "name", "latitude", "longitude", "elevation_ft",
            "date", "time", "timezone", "duration_min", "distance_from_previous_mi",
            "weather_condition", "temperature", "temperature_unit",
            "wind_speed", "wind_direction", "precipitation_pct"
    };

    @Override public String formatId() { return "csv"; }
    @Override public String contentType() { return "text/csv; charset=utf-8"; }
    @Override public String fileExtension() { return "csv"; }
    @Override public boolean requiresWeather() { return true; }

    @Override
    public byte[] write(ExportContext context) {
        List<WaypointDto> waypoints = context.getRoute().getWaypoints();
        List<RouteData.RouteSegment> segments = context.getRouteData().getSegments();

        StringBuilder sb = new StringBuilder();
        sb.append(UTF8_BOM);
        appendRow(sb, (Object[]) HEADERS);

        for (int i = 0; i < waypoints.size(); i++) {
            WaypointDto wp = waypoints.get(i);
            WeatherData weather = context.getWaypointWeatherBySequence().get(wp.getSequence());

            String distanceMi = "";
            if (i > 0 && segments != null && i - 1 < segments.size()) {
                Double d = segments.get(i - 1).getDistance();
                if (d != null) {
                    distanceMi = formatDouble(d * METERS_TO_MILES, 2);
                }
            }

            appendRow(sb,
                    wp.getSequence(),
                    nullToEmpty(wp.getLocationName()),
                    formatDouble(wp.getLatitude(), 6),
                    formatDouble(wp.getLongitude(), 6),
                    wp.getElevation() != null ? formatDouble(wp.getElevation() * METERS_TO_FEET, 0) : "",
                    nullToEmpty(wp.getDate()),
                    nullToEmpty(wp.getTime()),
                    nullToEmpty(wp.getTimezone()),
                    wp.getDurationMin() != null ? wp.getDurationMin().toString() : "",
                    distanceMi,
                    weather != null ? nullToEmpty(weather.getCondition()) : "",
                    weather != null && weather.getTemperature() != null ? weather.getTemperature().toString() : "",
                    weather != null ? nullToEmpty(weather.getTemperatureUnit()) : "",
                    weather != null ? nullToEmpty(weather.getWindSpeed()) : "",
                    weather != null ? nullToEmpty(weather.getWindDirection()) : "",
                    weather != null && weather.getPrecipitationProbability() != null
                            ? weather.getPrecipitationProbability().toString() : ""
            );
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder sb, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(cells[i] == null ? "" : cells[i].toString()));
        }
        sb.append("\r\n");
    }

    // RFC 4180: only quote when needed; double internal quotes.
    static String quote(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String formatDouble(Double value, int decimals) {
        if (value == null) return "";
        return String.format("%." + decimals + "f", value);
    }
}
