package com.pjr22.tripweather.export;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.springframework.stereotype.Component;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.WaypointDto;

@Component
public class GpxExporter implements RouteExporter {

    private static final String GPX_NS = "http://www.topografix.com/GPX/1/1";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String GPX_SCHEMA = "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd";

    private static final DateTimeFormatter LOCAL_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final XMLOutputFactory xmlFactory = XMLOutputFactory.newInstance();

    @Override public String formatId() { return "gpx"; }
    @Override public String contentType() { return "application/gpx+xml"; }
    @Override public String fileExtension() { return "gpx"; }
    @Override public boolean requiresWeather() { return false; }

    @Override
    public byte[] write(ExportContext context) {
        RouteDto route = context.getRoute();
        List<WaypointDto> waypoints = route.getWaypoints();
        List<List<Double>> geometry = context.getRouteData().getGeometry();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            XMLStreamWriter writer = xmlFactory.createXMLStreamWriter(baos, "UTF-8");
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("gpx");
            writer.writeAttribute("version", "1.1");
            writer.writeAttribute("creator", "Trip Weather");
            writer.writeDefaultNamespace(GPX_NS);
            writer.writeNamespace("xsi", XSI_NS);
            writer.writeAttribute(XSI_NS, "schemaLocation", GPX_SCHEMA);

            writeMetadata(writer, route.getName());

            // <wpt>: standalone waypoints (POIs)
            for (WaypointDto wp : waypoints) {
                writePoint(writer, "wpt", wp, true);
            }

            // <rte>: planned route as a sequence of waypoints
            writer.writeStartElement("rte");
            writeText(writer, "name", route.getName());
            for (WaypointDto wp : waypoints) {
                writePoint(writer, "rtept", wp, false);
            }
            writer.writeEndElement(); // rte

            // <trk>: high-resolution path from ORS
            writer.writeStartElement("trk");
            writeText(writer, "name", route.getName());
            writer.writeStartElement("trkseg");
            for (List<Double> coord : geometry) {
                writeTrackPoint(writer, coord);
            }
            writer.writeEndElement(); // trkseg
            writer.writeEndElement(); // trk

            writer.writeEndElement(); // gpx
            writer.writeEndDocument();
            writer.flush();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to write GPX", e);
        }
        return baos.toByteArray();
    }

    private void writeMetadata(XMLStreamWriter writer, String name) throws XMLStreamException {
        writer.writeStartElement("metadata");
        writeText(writer, "name", name);
        writer.writeStartElement("time");
        writer.writeCharacters(ZonedDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_INSTANT));
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private void writePoint(XMLStreamWriter writer, String element, WaypointDto wp, boolean includeTime)
            throws XMLStreamException {
        writer.writeStartElement(element);
        writer.writeAttribute("lat", String.valueOf(wp.getLatitude()));
        writer.writeAttribute("lon", String.valueOf(wp.getLongitude()));
        if (wp.getElevation() != null) {
            writeText(writer, "ele", String.valueOf(wp.getElevation()));
        }
        if (includeTime) {
            String iso = waypointToIsoUtc(wp);
            if (iso != null) {
                writeText(writer, "time", iso);
            }
        }
        if (wp.getLocationName() != null && !wp.getLocationName().isBlank()) {
            writeText(writer, "name", wp.getLocationName());
        }
        writer.writeEndElement();
    }

    private void writeTrackPoint(XMLStreamWriter writer, List<Double> coord) throws XMLStreamException {
        if (coord == null || coord.size() < 2) return;
        writer.writeStartElement("trkpt");
        // Geometry order is [lon, lat, ele].
        writer.writeAttribute("lat", String.valueOf(coord.get(1)));
        writer.writeAttribute("lon", String.valueOf(coord.get(0)));
        if (coord.size() >= 3) {
            writeText(writer, "ele", String.valueOf(coord.get(2)));
        }
        writer.writeEndElement();
    }

    private void writeText(XMLStreamWriter writer, String element, String value) throws XMLStreamException {
        if (value == null) return;
        writer.writeStartElement(element);
        writer.writeCharacters(value);
        writer.writeEndElement();
    }

    // Converts the waypoint's local date/time/timezone into ISO 8601 UTC
    // (e.g. 2026-06-01T16:00:00Z). Returns null if any required field is missing
    // or the timezone can't be parsed — GPX <time> is optional.
    static String waypointToIsoUtc(WaypointDto wp) {
        if (wp.getDate() == null || wp.getDate().isBlank()
                || wp.getTime() == null || wp.getTime().isBlank()) {
            return null;
        }
        try {
            LocalDateTime local = LocalDateTime.parse(wp.getDate() + " " + wp.getTime(), LOCAL_DT);
            String tz = (wp.getTimezone() == null || wp.getTimezone().isBlank()) ? "UTC" : wp.getTimezone();
            ZonedDateTime zoned = local.atZone(ZoneId.of(tz));
            return zoned.withZoneSameInstant(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_INSTANT);
        } catch (Exception e) {
            return null;
        }
    }

}
