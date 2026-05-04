package com.pjr22.tripweather.export;

import java.util.Map;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.model.WeatherData;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// Everything an exporter might need, assembled once by ExportService and passed
// to write(). Keyed by waypoint sequence (1-based) so the exporter can look up
// the same waypoint it's iterating over without juggling UUIDs.
@Getter
@RequiredArgsConstructor
public class ExportContext {

    private final RouteDto route;

    private final RouteData routeData;

    private final Map<Integer, WeatherData> waypointWeatherBySequence;
}
