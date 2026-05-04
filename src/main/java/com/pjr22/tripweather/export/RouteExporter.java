package com.pjr22.tripweather.export;

// One implementation per export format (gpx, kml, kmz, geojson, csv). Spring
// collects every RouteExporter bean and ExportService dispatches to the right
// one based on formatId(). Adding a new format = adding a new bean.
public interface RouteExporter {

    String formatId();

    String contentType();

    String fileExtension();

    // Whether this format embeds per-waypoint weather. ExportService skips the
    // N weather API calls when no exporter for the requested format wants them.
    boolean requiresWeather();

    byte[] write(ExportContext context);
}
